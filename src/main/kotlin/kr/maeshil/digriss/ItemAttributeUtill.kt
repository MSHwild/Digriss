package kr.maeshil.digriss

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.inventory.ItemStack

object ItemAttributeUtil {

    fun isCustomItem(item: ItemStack): Boolean {
        return CustomStack.byItemStack(item) != null
    }

    fun getCustomItemId(item: ItemStack): String? {
        return CustomStack.byItemStack(item)?.namespacedID
    }

    fun getWeaponData(item: ItemStack): WeaponData? {
        val id = getCustomItemId(item) ?: return null
        return WeaponRegistry.get(id)
    }
}