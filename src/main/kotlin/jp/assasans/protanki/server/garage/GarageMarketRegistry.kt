package jp.assasans.protanki.server.garage

import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.readText
import kotlin.reflect.KClass
import com.squareup.moshi.Moshi
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import jp.assasans.protanki.server.IResourceManager

interface IGarageMarketRegistry {
  val items: MutableMap<String, ServerGarageItem>

  suspend fun load()
}

class GarageItemGroup(
  val itemType: GarageItemType,
  val type: KClass<out ServerGarageItem>,
  val directory: String
)

class GarageMarketRegistry : IGarageMarketRegistry, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val json by inject<Moshi>()
  private val resourceManager by inject<IResourceManager>()

  private val groups = listOf(
    GarageItemGroup(GarageItemType.Weapon, ServerGarageItemWeapon::class, "weapons"),
    GarageItemGroup(GarageItemType.Hull, ServerGarageItemHull::class, "hulls"),
    GarageItemGroup(GarageItemType.Paint, ServerGarageItemPaint::class, "paints"),
    GarageItemGroup(GarageItemType.Supply, ServerGarageItemSupply::class, "supplies"),
    GarageItemGroup(GarageItemType.Subscription, ServerGarageItemSubscription::class, "subscriptions"),
    GarageItemGroup(GarageItemType.Kit, ServerGarageItemKit::class, "kits"),
    GarageItemGroup(GarageItemType.Present, ServerGarageItemPresent::class, "presents")
  )

  override val items: MutableMap<String, ServerGarageItem> = mutableMapOf()

  override suspend fun load() {
    for(group in groups) {
      logger.debug { "Loading garage item group ${group.itemType.name}..." }

      resourceManager.get("garage/items/${group.directory}").absolute().forEachDirectoryEntry { entry ->
        if(entry.extension != "json") return@forEachDirectoryEntry

        val item = json
          .adapter(group.type.java)
          .failOnUnknown()
          .fromJson(entry.readText())!!

        items[item.id] = item

        logger.debug { "  > Loaded garage item ${item.id} -> ${item.name}" }
      }
    }
  }
}

fun IGarageMarketRegistry.get(id: String): ServerGarageItem {
  return items[id] ?: throw Exception("Item $id not found")
}

inline fun <reified T : ServerGarageItem> ServerGarageItem.cast(): T {
  if(this is T) return this
  throw Exception("Incompatible type: expected ${T::class.simpleName}, got ${this::class.simpleName}")
}
