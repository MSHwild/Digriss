package kr.maeshil.digriss.nation

import org.bukkit.Chunk
import org.bukkit.Location
import java.util.UUID

object NationManager {

    private val nations = mutableMapOf<String, Nations>()

    fun createNation(name: String, owner: UUID, loc: Location): Boolean {
        if (nations.containsKey(name)) return false

        val newNation = Nations(name, owner)
        newNation.members.add(owner)
        nations[name] = newNation

        // BlueMap에 국가 깃발/대표 마커 추가 (Y=64 고정)
        BlueMapBridge.addNationMarker(name, loc)
        return true
    }

    fun claimChunk(nationName: String, chunk: Chunk): Boolean {
        val nation = nations[nationName] ?: return false
        val chunkKey = "${chunk.world.name},${chunk.x},${chunk.z}"

        if (isClaimed(chunkKey)) return false

        nation.claims.add(chunkKey)

        // 지형 로드 없이 좌표 기반으로 블루맵 영역 업데이트
        BlueMapBridge.updateTerritory(nation, chunk.world.name)
        return true
    }

    fun isClaimed(chunkKey: String): Boolean {
        return nations.values.any { it.claims.contains(chunkKey) }
    }

    fun getNation(name: String): Nations? = nations[name]

    fun deleteNation(name: String) {
        nations.remove(name)
        BlueMapBridge.removeNationMarker(name)
        BlueMapBridge.removeTerritory(name)
    }
}