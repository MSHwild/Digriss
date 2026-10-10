package kr.maeshil.digriss.util

import kr.maeshil.digriss.Digriss
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class ItemAttributeStore(private val plugin: Digriss) {

    // 버전과 상관없는 이름으로 저장 (1.21.3부터 generic.max_health → max_health 로 바뀜)
    private fun attrKey(attribute: Attribute) =
        NamespacedKey(plugin, "attr_${kr.maeshil.digriss.Attrs.shortKey(attribute)}")

    // 예전(1.21.1)에 저장한 이름. 읽을 때만 씀
    private fun legacyAttrKey(attribute: Attribute) =
        NamespacedKey(plugin, "attr_${attribute.key.key}")

    private fun modifierKey(attribute: Attribute) =
        NamespacedKey(plugin, "mod_${kr.maeshil.digriss.Attrs.shortKey(attribute)}")

    fun getEffectiveAttributes(item: ItemStack, slot: EquipmentSlot = EquipmentSlot.HAND): Map<Attribute, Double> {
        val result = mutableMapOf<Attribute, Double>()

        item.type.getDefaultAttributeModifiers(slot).asMap().forEach { (attr, modifiers) ->
            result[attr] = modifiers.sumOf { it.amount }
        }

        item.itemMeta?.attributeModifiers?.asMap()?.forEach { (attr, modifiers) ->
            result[attr] = modifiers.sumOf { it.amount }
        }

        return result
    }

    fun snapshotAttributes(item: ItemStack, slot: EquipmentSlot = EquipmentSlot.HAND) {
        val meta = item.itemMeta ?: return
        val container = meta.persistentDataContainer

        getEffectiveAttributes(item, slot).forEach { (attribute, value) ->
            if (!container.has(attrKey(attribute), PersistentDataType.DOUBLE) && !container.has(legacyAttrKey(attribute), PersistentDataType.DOUBLE)) {
                container.set(attrKey(attribute), PersistentDataType.DOUBLE, value)
            }
        }

        item.itemMeta = meta
    }

    fun getStoredAttributes(item: ItemStack): Map<Attribute, Double> {
        val meta = item.itemMeta ?: return emptyMap()
        val container = meta.persistentDataContainer
        val result = mutableMapOf<Attribute, Double>()

        kr.maeshil.digriss.Attrs.all().forEach { attribute ->
            val key = listOf(attrKey(attribute), legacyAttrKey(attribute)).firstOrNull { container.has(it, PersistentDataType.DOUBLE) }
            if (key != null) {
                result[attribute] = container.get(key, PersistentDataType.DOUBLE) ?: 0.0
            }
        }
        return result
    }

    /**
     * 모든 슬롯(HAND/HEAD/CHEST/LEGS/FEET 등)을 전부 순회하며 해당 어트리뷰트의
     * 기존 모디파이어를 지운다. 예전 버전에서 잘못된 슬롯으로 붙은 모디파이어까지
     * 확실히 제거하기 위함.
     */
    private fun removeAllModifiers(meta: org.bukkit.inventory.meta.ItemMeta, attribute: Attribute) {
        EquipmentSlot.values().forEach { s ->
            meta.getAttributeModifiers(s)?.get(attribute)?.toList()?.forEach {
                meta.removeAttributeModifier(attribute, it)
            }
        }
        // 슬롯 지정 없는 전체 모디파이어도 한 번 더 정리
        meta.attributeModifiers?.get(attribute)?.toList()?.forEach {
            meta.removeAttributeModifier(attribute, it)
        }
    }

    fun updateAttribute(
        item: ItemStack,
        target: Attribute,
        newValue: Double,
        slot: EquipmentSlot = EquipmentSlot.HAND
    ): ItemStack {
        snapshotAttributes(item, slot)

        val meta = item.itemMeta ?: return item
        val container = meta.persistentDataContainer

        container.set(attrKey(target), PersistentDataType.DOUBLE, newValue)
        if (legacyAttrKey(target) != attrKey(target)) container.remove(legacyAttrKey(target)) // 예전 이름은 정리

        // 대상 어트리뷰트의 기존 모디파이어를 모든 슬롯에서 확실히 제거
        removeAllModifiers(meta, target)

        val slotGroup = when (slot) {
            EquipmentSlot.HAND -> EquipmentSlotGroup.MAINHAND
            EquipmentSlot.OFF_HAND -> EquipmentSlotGroup.OFFHAND
            EquipmentSlot.HEAD -> EquipmentSlotGroup.HEAD
            EquipmentSlot.CHEST -> EquipmentSlotGroup.CHEST
            EquipmentSlot.LEGS -> EquipmentSlotGroup.LEGS
            EquipmentSlot.FEET -> EquipmentSlotGroup.FEET
            else -> EquipmentSlotGroup.ANY
        }

        val modifier = AttributeModifier(
            modifierKey(target),
            newValue,
            AttributeModifier.Operation.ADD_NUMBER,
            slotGroup
        )
        meta.addAttributeModifier(target, modifier)

        item.itemMeta = meta
        return item
    }
}