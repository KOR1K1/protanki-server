package jp.assasans.protanki.server.client

import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.readText
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.primaryConstructor
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent
import jp.assasans.protanki.server.*
import jp.assasans.protanki.server.battles.*
import jp.assasans.protanki.server.battles.map.IMapRegistry
import jp.assasans.protanki.server.commands.*
import jp.assasans.protanki.server.exceptions.UnknownCommandCategoryException
import jp.assasans.protanki.server.exceptions.UnknownCommandException
import jp.assasans.protanki.server.garage.*
import jp.assasans.protanki.server.lobby.chat.ILobbyChatManager

suspend fun Command.send(socket: UserSocket) = socket.send(this)
suspend fun Command.send(player: BattlePlayer) = player.socket.send(this)
suspend fun Command.send(tank: BattleTank) = tank.socket.send(this)

suspend fun UserSocket.sendChat(message: String) = Command(
  CommandName.SendChatMessageClient,
  listOf(
    ChatMessage(
      name = "",
      rang = 0,
      message = message,
      system = true,
      yellow = true
    ).toJson()
  )
).send(this)

@OptIn(ExperimentalStdlibApi::class)
class UserSocket(
  private val socket: Socket
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val packetProcessor = PacketProcessor()
  private val encryption = EncryptionTransformer()
  private val server by inject<ISocketServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val resourceManager by inject<IResourceManager>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val garageItemConverter by inject<IGarageItemConverter>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val lobbyChatManager by inject<ILobbyChatManager>()
  private val json by inject<Moshi>()

  private val input: ByteReadChannel = socket.openReadChannel()
  private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)

  private val lock: Semaphore = Semaphore(1)
  // private val sendQueue: Queue<Command> = LinkedList()

  private val socketJobs: MutableList<Job> = mutableListOf()

  val remoteAddress: SocketAddress
    get() = socket.remoteAddress

  var active: Boolean = false

  var locale: SocketLocale? = null

  var user: User? = null
  var selectedBattle: Battle? = null
  var screen: Screen? = null

  val battle: Battle?
    get() = battlePlayer?.battle

  val battlePlayer: BattlePlayer?
    get() = battleProcessor.battles
      .flatMap { battle -> battle.players }
      .singleOrNull { player -> player.socket == this }

  private suspend fun deactivate() {
    active = false

    val player = battlePlayer
    if(player != null) { // Remove player from battle
      player.deactivate()
      player.battle.players.remove(player)
    }

    logger.debug { "Cancelling ${socketJobs.size} jobs..." }
    socketJobs.forEach { job ->
      if(job.isActive) job.cancel()
    }

    server.players.remove(this)
  }

  suspend fun send(command: Command) {
    lock.withPermit {
      try {
        output.writeFully(command.serialize().toByteArray())
      } catch(exception: IOException) {
        logger.warn(exception) { "${socket.remoteAddress} thrown an exception" }
        deactivate()
        return
      }

      if(
        command.name != CommandName.Pong &&
        command.name != CommandName.ClientMove &&
        command.name != CommandName.ClientFullMove &&
        command.name != CommandName.ClientRotateTurret &&
        command.name != CommandName.ClientMovementControl
      ) { // Too verbose
        if(
          command.name == CommandName.LoadResources ||
          command.name == CommandName.InitLocale ||
          command.name == CommandName.InitShotsData ||
          command.name == CommandName.InitGarageItems ||
          command.name == CommandName.InitGarageMarket
        ) { // Too long
          logger.trace { "Sent command ${command.name} ${command.args.drop(1)}" }
        } else {
          logger.trace { "Sent command ${command.name} ${command.args}" }
        }
      }
    }
  }

  private val dependenciesChannel: Channel<Int> = Channel(32) // TODO(Assasans)
  private val loadedDependencies: MutableList<Int> = mutableListOf()
  private var lastDependencyId = 1

  suspend fun loadDependency(resources: String): Int {
    Command(
      CommandName.LoadResources,
      listOf(
        resources,
        lastDependencyId.toString()
      )
    ).send(this)

    return lastDependencyId++
  }

  suspend fun markDependencyLoaded(id: Int) {
    dependenciesChannel.send(id)
  }

  suspend fun awaitDependency(id: Int) {
    if(loadedDependencies.contains(id)) return

    while(true) {
      val loaded = dependenciesChannel.receive()
      loadedDependencies.add(loaded)

      if(loaded == id) break
    }
  }

  private suspend fun processPacket(packet: String) {
    try {
      // val end = packet.takeLast(Command.Delimiter.length)
      // if(end != Command.Delimiter) throw Exception("Invalid packet end: $end")

      // val decrypted = encryption.decrypt(packet.dropLast(Command.Delimiter.length))
      if(packet.isEmpty()) return

      // logger.debug { "PKT: $packet" }
      val decrypted = encryption.decrypt(packet)

      // logger.debug { "Decrypt: $packet -> $decrypted" }

      val command = Command()
      command.readFrom(decrypted.toByteArray())

      if(
        command.name != CommandName.Ping &&
        command.name != CommandName.Move &&
        command.name != CommandName.FullMove &&
        command.name != CommandName.RotateTurret &&
        command.name != CommandName.MovementControl
      ) { // Too verbose
        logger.trace { "Received command ${command.name} ${command.args}" }
      }

      if(command.side != CommandSide.Server) throw Exception("Unsupported command: ${command.category}::${command.name}")

      val handler = commandRegistry.getHandler(command.name)
      if(handler != null) {
        try {
          val instance = handler.type.primaryConstructor!!.call()
          val args = mutableMapOf<KParameter, Any?>(
            Pair(
              handler.function.parameters.single { parameter -> parameter.kind == KParameter.Kind.INSTANCE },
              instance
            ),
            Pair(
              handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[0],
              this
            )
          )

          when(handler.argsBehaviour) {
            ArgsBehaviourType.Arguments -> {
              args.putAll(handler.args.mapIndexed { index, parameter ->
                val value = command.args[index]

                Pair(parameter, CommandArgs.convert(parameter.type, value))
              })
            }

            ArgsBehaviourType.Raw       -> {
              val argsParameter = handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[1]
              args[argsParameter] = CommandArgs(command.args)
            }
          }

          // logger.debug { "Handler ${handler.name} call arguments: ${args.map { argument -> "${argument.key.type}" }}" }

          handler.function.callSuspendBy(args)
        } catch(exception: Throwable) {
          logger.error(exception) { "Failed to call ${command.name} handler" }
        }

        return
      }

      when(command.name) {
        CommandName.ShowFriendsList -> {
          Command(
            CommandName.ShowFriendsList,
            listOf(ShowFriendsModalData().toJson())
          ).send(this)
        }

        else                        -> {}
      }
    } catch(exception: UnknownCommandCategoryException) {
      logger.warn { "Unknown command category: ${exception.category}" }
    } catch(exception: UnknownCommandException) {
      logger.warn { "Unknown command: ${exception.category}::${exception.command}" }
    } catch(exception: Exception) {
      logger.error(exception) { "An exception occurred" }
    }
  }

  suspend fun initBattleLoad() {
    Command(CommandName.StartLayoutSwitch, listOf("BATTLE")).send(this)
    Command(CommandName.UnloadBattleSelect).send(this)
    Command(CommandName.StartBattle).send(this)
    Command(CommandName.UnloadChat).send(this)
  }

  suspend fun <R> runConnected(block: suspend UserSocket.() -> R) {
    coroutineScope {
      val job = launch {
        block.invoke(this@UserSocket)
      }

      socketJobs.add(job)
      job.invokeOnCompletion { socketJobs.remove(job) }
    }
  }

  suspend fun handle() {
    active = true

    // awaitDependency can deadlock execution if suspended
    GlobalScope.launch { initClient() }

    try {
      while(!(input.isClosedForRead || input.isClosedForWrite)) {
        val buffer: ByteArray;
        try {
          buffer = input.readAvailable()
          packetProcessor.write(buffer)
        } catch(exception: IOException) {
          logger.warn(exception) { "${socket.remoteAddress} thrown an exception" }
          deactivate()

          break
        }

        // val packets = String(buffer).split(Command.Delimiter)

        // for(packet in packets) {
        // awaitDependency can deadlock execution if suspended
        //   GlobalScope.launch { processPacket(packet) }
        // }

        while(true) {
          val packet = packetProcessor.tryGetPacket() ?: break

          // awaitDependency can deadlock execution if suspended
          GlobalScope.launch { processPacket(packet) }
        }
      }

      logger.debug { "${socket.remoteAddress} end of data" }

      deactivate()
    } catch(exception: Throwable) {
      logger.error(exception) { "An exception occurred" }

      // withContext(Dispatchers.IO) {
      //   socket.close()
      // }
    }
  }

  suspend fun loadGarageResources() {
    awaitDependency(loadDependency(resourceManager.get("resources/garage.json").readText()))
  }

  suspend fun loadLobbyResources() {
    awaitDependency(loadDependency(resourceManager.get("resources/lobby.json").readText()))
  }

  suspend fun loadLobby() {
    Command(CommandName.StartLayoutSwitch, listOf("BATTLE_SELECT")).send(this)

    screen = Screen.BattleSelect

    Command(CommandName.InitPremium, listOf(InitPremiumData().toJson())).send(this)

    val user = user ?: throw Exception("No User")

    Command(
      CommandName.InitPanel,
      listOf(
        InitPanelData(
          name = user.username,
          crystall = user.crystals,
          rang = user.rank.value,
          score = user.score,
          currentRankScore = user.currentRankScore,
          next_score = user.nextRankScore
        ).toJson()
      )
    ).send(this)

    // Command(CommandName.UpdateRankProgress, listOf("3668")).send(this)

    Command(
      CommandName.InitFriendsList,
      listOf(
        InitFriendsListData(
          friends = listOf(
            // FriendEntry(id = "Luminate", rank = 16, online = false),
            // FriendEntry(id = "MoscowCity", rank = 18, online = true)
          )
        ).toJson()
      )
    ).send(this)

    loadLobbyResources()

    Command(CommandName.EndLayoutSwitch, listOf("BATTLE_SELECT", "BATTLE_SELECT")).send(this)

    Command(
      CommandName.ShowAchievements,
      listOf(ShowAchievementsData(ids = listOf(1, 3)).toJson())
    ).send(this)

    initChatMessages()
    initBattleList()
  }

  private suspend fun initClient() {
    Command(CommandName.InitExternalModel, listOf("http://localhost/")).send(this)
    Command(
      CommandName.InitRegistrationModel,
      listOf(
        // "{\"bgResource\": 122842, \"enableRequiredEmail\": false, \"maxPasswordLength\": 100, \"minPasswordLength\": 1}"
        InitRegistrationModelData(
          enableRequiredEmail = false
        ).toJson()
      )
    ).send(this)

    Command(CommandName.InitLocale, listOf(resourceManager.get("lang/ru.json").readText())).send(this)

    awaitDependency(loadDependency(resourceManager.get("resources/auth.json").readText()))
    Command(CommandName.MainResourcesLoaded).send(this)
  }

  suspend fun initBattleList() {
    val mapsParsed = json
      .adapter<List<Map>>(Types.newParameterizedType(List::class.java, Map::class.java))
      .fromJson(resourceManager.get("maps.json").readText())!!

    Command(
      CommandName.InitBattleCreate,
      listOf(
        InitBattleCreateData(
          battleLimits = listOf(
            BattleLimit(battleMode = BattleMode.Deathmatch, scoreLimit = 999, timeLimitInSec = 59940),
            BattleLimit(battleMode = BattleMode.TeamDeathmatch, scoreLimit = 999, timeLimitInSec = 59940),
            BattleLimit(battleMode = BattleMode.CaptureTheFlag, scoreLimit = 999, timeLimitInSec = 59940),
            BattleLimit(battleMode = BattleMode.ControlPoints, scoreLimit = 999, timeLimitInSec = 59940)
          ),
          maps = mapsParsed.filter { userMap -> mapRegistry.maps.any { map -> map.name == userMap.mapId && map.theme.clientKey == userMap.theme } }
        ).toJson()
      )
    ).send(this)

    Command(
      CommandName.InitBattleSelect,
      listOf(
        InitBattleSelectData(
          battles = battleProcessor.battles.map { battle -> battle.toBattleData() }
        ).toJson()
      )
    ).send(this)
  }

  suspend fun initGarage() {
    val user = user ?: throw Exception("No User")

    val itemsParsed = mutableListOf<GarageItem>()
    val marketParsed = mutableListOf<GarageItem>()

    val marketItems = marketRegistry.items

    marketItems.forEach { (_, marketItem) ->
      val userItem = user.items.singleOrNull { it.marketItem == marketItem }
      val clientMarketItems = when(marketItem) {
        is ServerGarageItemWeapon       -> garageItemConverter.toClientWeapon(marketItem)
        is ServerGarageItemHull         -> garageItemConverter.toClientHull(marketItem)
        is ServerGarageItemPaint        -> listOf(garageItemConverter.toClientPaint(marketItem))
        is ServerGarageItemSupply       -> listOf(garageItemConverter.toClientSupply(marketItem))
        is ServerGarageItemSubscription -> listOf(garageItemConverter.toClientSubscription(marketItem))
        is ServerGarageItemKit          -> listOf(garageItemConverter.toClientKit(marketItem))
        is ServerGarageItemPresent      -> listOf(garageItemConverter.toClientPresent(marketItem))

        else                            -> throw NotImplementedError("Not implemented: ${marketItem::class.simpleName}")
      }

      // if(marketItem is ServerGarageItemSupply) return@forEach
      // if(marketItem is ServerGarageItemSubscription) return@forEach
      // if(marketItem is ServerGarageItemKit) return@forEach

      if(userItem != null) {
        // Add user item
        if(userItem is ServerGarageUserItemSupply) {
          clientMarketItems.single().count = userItem.count
        }

        if(userItem is IServerGarageUserItemWithModification) {
          clientMarketItems.forEach clientMarketItems@{ clientItem ->
            // Add current and previous modifications as user items
            // if(clientItem.modificationID!! <= userItem.modification) itemsParsed.add(clientItem)

            // if(clientItem.modificationID!! < userItem.modification) return@clientMarketItems
            if(clientItem.modificationID == userItem.modificationIndex) itemsParsed.add(clientItem)
            else marketParsed.add(clientItem)
          }
        } else {
          itemsParsed.addAll(clientMarketItems)
        }
      } else {
        // Add market item
        marketParsed.addAll(clientMarketItems)
      }
    }

    marketParsed
      .filter { item -> item.type == GarageItemType.Kit }
      .forEach { item ->
        if(item.kit == null) throw Exception("Kit is null")

        val ownsAll = item.kit.kitItems.all { kitItem ->
          val id = kitItem.id.substringBeforeLast("_")
          val modification = kitItem.id
            .substringAfterLast("_")
            .drop(1) // Drop 'm' letter
            .toInt()

          marketParsed.none { marketItem -> marketItem.id == id && marketItem.modificationID == modification }
        }
        if(ownsAll) {
          marketParsed.remove(item)

          logger.debug { "Removed kit ${item.name} from market: user owns all items" }
        }
      }

    Command(CommandName.InitGarageItems, listOf(InitGarageItemsData(items = itemsParsed).toJson())).send(this)
    Command(
      CommandName.InitMountedItem,
      listOf(user.equipment.hull.mountName, user.equipment.hull.modification.object3ds.toString())
    ).send(this)
    Command(
      CommandName.InitMountedItem,
      listOf(user.equipment.weapon.mountName, user.equipment.weapon.modification.object3ds.toString())
    ).send(this)
    Command(
      CommandName.InitMountedItem,
      listOf(user.equipment.paint.mountName, user.equipment.paint.marketItem.coloring.toString())
    ).send(this)
    Command(CommandName.InitGarageMarket, listOf(InitGarageMarketData(items = marketParsed).toJson())).send(this)

    // logger.debug { "User items:" }
    // itemsParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
    //
    // logger.debug { "Market items:" }
    // marketParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
  }

  suspend fun updateCrystals() {
    val user = user ?: throw Exception("User data is not loaded")

    Command(CommandName.SetCrystals, listOf(user.crystals.toString())).send(this)
  }

  suspend fun initChatMessages() {
    val user = user ?: throw Exception("User data is not loaded")

    val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS", Locale.ROOT)

    Command(
      CommandName.InitMessages,
      listOf(
        InitChatMessagesData(
          messages = lobbyChatManager.messages + listOf(
            ChatMessage(name = "", system = true, rang = 0, message = "=== ProTanki Server ==="),
            ChatMessage(name = "", system = true, rang = 0, message = "GitHub: https://github.com/Assasans/protanki-server"),
            ChatMessage(name = "", system = true, rang = 0, message = "Loaded maps: ${mapRegistry.maps.size}"),
            ChatMessage(name = "", system = true, rang = 0, message = "Loaded garage items: ${marketRegistry.items.size}"),
            ChatMessage(name = "", system = true, rang = 0, message = "Server time: ${time.toJavaLocalDateTime().format(formatter)}"),
            ChatMessage(name = "", system = true, rang = 0, message = "Online players: ${server.players.size}")
          )
        ).toJson(),
        InitChatSettings(
          selfName = user.username
        ).toJson()
      )
    ).send(this)
  }
}

data class InitBonusesData(
  @Json val init_bonuses: List<Any> = listOf() // TODO(Assasans)
)

inline fun <reified T : Any> T.toJson(json: Moshi): String {
  return json.adapter(T::class.java).toJson(this)
}

inline fun <reified T : Any> T.toJson(): String {
  val json = KoinJavaComponent.inject<Moshi>(Moshi::class.java).value
  return json.adapter(T::class.java).toJson(this)
}

fun <T : Any> Moshi.toJson(value: T): String {
  return adapter<T>(value::class.java).toJson(value)
}

data class InitBattleModelData(
  @Json val battleId: String,
  @Json val map_id: String,
  @Json val mapId: Int,
  @Json val kick_period_ms: Int = 125000,
  @Json val invisible_time: Int = 3500,
  @Json val spectator: Boolean = true,
  @Json val reArmorEnabled: Boolean,
  @Json val active: Boolean = true,
  @Json val dustParticle: Int = 110001,
  @Json val minRank: Int = 3,
  @Json val maxRank: Int = 30,
  @Json val skybox: String,
  @Json val sound_id: Int = 584396,
  @Json val map_graphic_data: String
)

data class BonusLightingData(
  @Json val attenuationBegin: Int = 100,
  @Json val attenuationEnd: Int = 500,
  @Json val color: Int,
  @Json val intensity: Int = 1,
  @Json val time: Int = 0
)

data class BonusData(
  @Json val lighting: BonusLightingData,
  @Json val id: String,
  @Json val resourceId: Int,
  @Json val lifeTime: Int = 30
)

data class InitBonusesDataData(
  @Json val bonuses: List<BonusData>,
  @Json val cordResource: Int = 1000065,
  @Json val parachuteInnerResource: Int = 170005,
  @Json val parachuteResource: Int = 170004,
  @Json val pickupSoundResource: Int = 269321
)

data class ShowFriendsModalData(
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class BattleUser(
  @Json val user: String,
  @Json val kills: Int = 0,
  @Json val score: Int = 0,
  @Json val suspicious: Boolean = false
)

abstract class ShowBattleInfoData(
  @Json val itemId: String,
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
  @Json val preview: Int,
  @Json val maxPeopleCount: Int,
  @Json val name: String,
  @Json val proBattle: Boolean = false,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val roundStarted: Boolean = true,
  @Json val spectator: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val withoutCrystals: Boolean,
  @Json val withoutSupplies: Boolean,
  @Json val proBattleEnterPrice: Int = 150,
  @Json val timeLeftInSec: Int,
  @Json val userPaidNoSuppliesBattle: Boolean = false,
  @Json val proBattleTimeLeftInSec: Int = -1
)

class ShowTeamBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean = false,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  proBattleEnterPrice: Int = 150,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,

  @Json val usersRed: List<BattleUser>,
  @Json val usersBlue: List<BattleUser>,

  @Json val scoreRed: Int = 0,
  @Json val scoreBlue: Int = 0,

  @Json val autoBalance: Boolean,
  @Json val friendlyFire: Boolean,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec
)

class ShowDmBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean = false,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  proBattleEnterPrice: Int = 150,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,

  @Json val users: List<BattleUser>,
  @Json val score: Int = 0,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec
)

data class BattleData(
  @Json val battleId: String,
  @Json val battleMode: BattleMode,
  @Json val map: String,
  @Json val maxPeople: Int,
  @Json val name: String,
  @Json val privateBattle: Boolean = false,
  @Json val proBattle: Boolean = false,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val preview: Int,
  @Json val suspicious: Boolean = false,
  @Json val users: List<String>
)

data class InitBattleSelectData(
  @Json val battles: List<BattleData>
)

data class BattleLimit(
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
)

data class Map(
  @Json val enabled: Boolean = true,
  @Json val mapId: String,
  @Json val mapName: String,
  @Json val maxPeople: Int,
  @Json val preview: Int,
  @Json val maxRank: Int,
  @Json val minRank: Int,
  @Json val supportedModes: List<String>,
  @Json val theme: String
)

data class InitBattleCreateData(
  @Json val maxRangeLength: Int = 7,
  @Json val battleCreationDisabled: Boolean = false,
  @Json val battleLimits: List<BattleLimit>,
  @Json val maps: List<Map>
)

data class ShowAchievementsData(
  @Json val ids: List<Int>
)

data class ChatMessage(
  @Json val name: String,
  @Json val rang: Int,
  @Json val chatPermissions: Int = 0,
  @Json val message: String,
  @Json val addressed: Boolean = false,
  @Json val chatPermissionsTo: Int = 0,
  @Json val nameTo: String = "",
  @Json val rangTo: Int = 0,
  @Json val system: Boolean = false,
  @Json val yellow: Boolean = false,
  @Json val sourceUserPremium: Boolean = false,
  @Json val targetUserPremium: Boolean = false
)

data class BattleChatMessage(
  @Json val nickname: String,
  @Json val rank: Int,
  @Json val chat_level: Int = 0,
  @Json val message: String,
  @Json val team_type: BattleTeam,
  @Json val system: Boolean = false,
  @Json val team: Boolean
)

data class InitChatMessagesData(
  @Json val messages: List<ChatMessage>
)

data class InitChatSettings(
  @Json val antiFloodEnabled: Boolean = true,
  @Json val typingSpeedAntifloodEnabled: Boolean = true,
  @Json val bufferSize: Int = 60,
  @Json val minChar: Int = 60,
  @Json val minWord: Int = 5,
  @Json val showLinks: Boolean = true,
  @Json val admin: Boolean = false,
  @Json val selfName: String,
  @Json val chatModeratorLevel: Int = 0,
  @Json val symbolCost: Int = 176,
  @Json val enterCost: Int = 880,
  @Json val chatEnabled: Boolean = true,
  @Json val linksWhiteList: List<String> = "http://gtanks-online.com/|http://vk.com/ebal"
    .toCharArray()
    .map(Char::toString)
)

data class AuthData(
  @Json val captcha: String,
  @Json val remember: Boolean,
  @Json val login: String,
  @Json val password: String
)

data class InitRegistrationModelData(
  @Json val bgResource: Int = 122842,
  @Json val enableRequiredEmail: Boolean = false,
  @Json val maxPasswordLength: Int = 100,
  @Json val minPasswordLength: Int = 1
)

data class InitPremiumData(
  @Json val left_time: Int = -1,
  @Json val needShowNotificationCompletionPremium: Boolean = false,
  @Json val needShowWelcomeAlert: Boolean = false,
  @Json val reminderCompletionPremiumTime: Int = 86400,
  @Json val wasShowAlertForFirstPurchasePremium: Boolean = false,
  @Json val wasShowReminderCompletionPremium: Boolean = true
)

data class InitPanelData(
  @Json val name: String,
  @Json val crystall: Int,
  @Json val email: String? = null,
  @Json val tester: Boolean = false,
  @Json val next_score: Int,
  @Json val place: Int = 0,
  @Json val rang: Int,
  @Json val rating: Int = 1,
  @Json val score: Int,
  @Json val currentRankScore: Int,
  @Json val hasDoubleCrystal: Boolean = false,
  @Json val durationCrystalAbonement: Int = -1,
  @Json val userProfileUrl: String = "http://ratings.generaltanks.com/ru/user/"
)

data class FriendEntry(
  @Json val id: String,
  @Json val rank: Int,
  @Json val online: Boolean
)

data class InitFriendsListData(
  @Json val friends: List<FriendEntry> = listOf(),
  @Json val incoming: List<FriendEntry> = listOf(),
  @Json val outcoming: List<FriendEntry> = listOf(),
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class ShowSettingsData(
  @Json val emailNotice: Boolean = false,
  @Json val email: String? = null,
  @Json val notificationEnabled: Boolean = true,
  @Json val showDamageEnabled: Boolean = true,
  @Json val isConfirmEmail: Boolean = false,
  @Json val authorizationUrl: String = "http://localhost/",
  @Json val linkExists: Boolean = false,
  @Json val snId: String = "vkontakte",
  @Json val passwordCreated: Boolean = true
)

data class BattleCreateData(
  @Json val withoutCrystals: Boolean,
  @Json val minRank: Int,
  @Json val reArmorEnabled: Boolean,
  @Json val maxPeopleCount: Int,
  @Json val autoBalance: Boolean,
  @Json val maxRank: Int,
  @Json val battleMode: BattleMode,
  @Json val mapId: String,
  @Json val name: String,
  @Json val scoreLimit: Int,
  @Json val friendlyFire: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val timeLimitInSec: Int,
  @Json val proBattle: Boolean,
  @Json val theme: String,
  @Json val withoutSupplies: Boolean,
  @Json val privateBattle: Boolean
)
