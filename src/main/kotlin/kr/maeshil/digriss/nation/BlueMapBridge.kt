package kr.maeshil.digriss.nation

import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import de.bluecolored.bluemap.api.markers.ShapeMarker
import de.bluecolored.bluemap.api.math.Color
import de.bluecolored.bluemap.api.math.Shape
import org.bukkit.Bukkit
import org.bukkit.Location
import kotlin.math.absoluteValue

object BlueMapBridge {

    private const val POI_SET_ID = "nation_markers"
    private const val TERRITORY_SET_ID = "nation_territory"
    private const val SITE_SET_ID = "resource_sites"

    // BlueMap이 없는 서버에서 BlueMap 클래스를 건드리면 오류가 나므로 먼저 확인
    private val available: Boolean get() = Bukkit.getPluginManager().getPlugin("BlueMap") != null

    // BlueMap API는 서버가 켜진 뒤 비동기로 준비되고, /bluemap reload 때마다 마커가 지워짐
    // → 준비될 때마다 action으로 전체 마커를 다시 그림 (메인 스레드에서 실행)
    fun onReady(action: () -> Unit) {
        if (!available) return
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") ?: return
        BlueMapAPI.onEnable { api ->
            hideListCoordinates(api)
            Bukkit.getScheduler().runTask(plugin, Runnable { action() })
        }
    }

    // 지도 왼쪽 목록에 국가 이름 아래로 보이는 좌표((x | y | z))를 숨기는 스타일을 웹앱에 등록
    private fun hideListCoordinates(api: BlueMapAPI) {
        try {
            val file = api.webApp.webRoot.resolve("assets/digriss.css")
            java.nio.file.Files.createDirectories(file.parent)
            java.nio.file.Files.writeString(
                file,
                ".side-menu .marker-item .marker-button>.info .stats{display:none}\n"
            )
            api.webApp.registerStyle("assets/digriss.css")
        } catch (e: Exception) {
            Bukkit.getLogger().warning("[Digriss] BlueMap 좌표 숨김 스타일 등록 실패: ${e.message}")
        }
    }

    // 🌟 BlueMap Color 생성자 (ARGB Int 패킹 방식 사용 - 클래스 충돌 100% 방지)
    // 🌟 16진수 ARGB/RGBA 정수 패킹 방식을 사용하여 생성자 타입 충돌 완전 해결
    private fun colorFor(nationName: String): Color {
        val hash = nationName.hashCode().absoluteValue
        val hue = (hash % 360) / 360f
        val rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 0.9f)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        val a = (255 * 0.45f).toInt() // 알파 (투명도)

        // BlueMap Color(int red, int green, int blue, int alpha) 대신
        // (r, g, b, a)를 하나의 Int 패킹 값으로 전달
        val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
        return Color(argb)
    }

    private fun lineColorFor(nationName: String): Color {
        val hash = nationName.hashCode().absoluteValue
        val hue = (hash % 360) / 360f
        val rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 0.9f)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF

        val argb = (255 shl 24) or (r shl 16) or (g shl 8) or b
        return Color(argb)
    }

    // 📍 1. 국가 깃발/아이콘 POI 마커 추가 (Y=64 고정)
    fun addNationMarker(nationName: String, loc: Location) {
        if (!available) return
        BlueMapAPI.getInstance().ifPresent { api ->
            val world = loc.world ?: return@ifPresent
            api.getWorld(world).ifPresent { blueWorld ->
                blueWorld.maps.forEach { map ->
                    val markerSet = map.markerSets.computeIfAbsent(POI_SET_ID) {
                        MarkerSet.builder()
                            .label("국가 목록")
                            .toggleable(true)
                            .defaultHidden(false)
                            .build()
                    }

                    val marker = POIMarker.builder()
                        .label("🏛️ $nationName")
                        .position(loc.x, 64.0, loc.z)
                        .build()

                    markerSet.markers[nationName] = marker
                }
            }
        }
    }

    // 자원 거점 마커 (별도 마커 묶음 "자원 거점")
    fun setSiteMarker(id: String, label: String, world: org.bukkit.World, x: Double, z: Double) {
        if (!available) return
        BlueMapAPI.getInstance().ifPresent { api ->
            api.getWorld(world).ifPresent { blueWorld ->
                blueWorld.maps.forEach { map ->
                    val set = map.markerSets.computeIfAbsent(SITE_SET_ID) {
                        MarkerSet.builder().label("자원 거점").toggleable(true).defaultHidden(false).build()
                    }
                    set.markers[id] = POIMarker.builder().label(label).position(x + 0.5, 64.0, z + 0.5).build()
                }
            }
        }
    }

    fun removeNationMarker(nationName: String) {
        if (!available) return
        BlueMapAPI.getInstance().ifPresent { api ->
            api.maps.forEach { map ->
                map.markerSets[POI_SET_ID]?.markers?.remove(nationName)
            }
        }
    }

    // 🗺️ 2. 국가 영토 사각형 표시 (Y=64 고정 및 depthTestEnabled(false))
    fun updateTerritory(nation: Nations, worldName: String) {
        if (!available) return
        BlueMapAPI.getInstance().ifPresent { api ->
            val bukkitWorld = Bukkit.getWorld(worldName) ?: return@ifPresent
            api.getWorld(bukkitWorld).ifPresent { blueWorld ->
                blueWorld.maps.forEach { map ->
                    val territorySet = map.markerSets.computeIfAbsent(TERRITORY_SET_ID) {
                        MarkerSet.builder()
                            .label("국가 영토")
                            .toggleable(true)
                            .defaultHidden(false)
                            .build()
                    }

                    territorySet.markers.keys.removeIf { it.startsWith("${nation.name}_chunk_") }

                    val fillColor = colorFor(nation.name)
                    val lineColor = lineColorFor(nation.name)

                    nation.claims
                        .filter { it.startsWith("$worldName,") }
                        .forEach { chunkKey ->
                            val parts = chunkKey.split(",")
                            if (parts.size < 3) return@forEach
                            val cx = parts[1].toIntOrNull() ?: return@forEach
                            val cz = parts[2].toIntOrNull() ?: return@forEach

                            val minX = cx * 16.0
                            val minZ = cz * 16.0
                            val maxX = minX + 16.0
                            val maxZ = minZ + 16.0

                            val shape = Shape.createRect(minX, minZ, maxX, maxZ)

                            val marker = ShapeMarker.builder()
                                .label("${nation.name} 영토")
                                .shape(shape, 64.0f)
                                .fillColor(fillColor)
                                .lineColor(lineColor)
                                .lineWidth(3)
                                .depthTestEnabled(false)
                                .build()
                            marker.isListed = false // 청크마다 목록에 뜨면 너무 길어서 지도 위에만 표시

                            territorySet.markers["${nation.name}_chunk_${cx}_${cz}"] = marker
                        }
                }
            }
        }
    }

    fun removeTerritory(nationName: String) {
        if (!available) return
        BlueMapAPI.getInstance().ifPresent { api ->
            api.maps.forEach { map ->
                map.markerSets[TERRITORY_SET_ID]?.markers?.keys?.removeIf { it.startsWith("${nationName}_chunk_") }
            }
        }
    }
}