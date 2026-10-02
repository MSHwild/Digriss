package kr.maeshil.digriss

import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player

// 효과음 모음: 같은 상황엔 같은 소리가 나도록 한 곳에서 관리 (음량·높낮이 조절도 여기서)
object Sounds {

    fun play(player: Player, sound: Sound, volume: Float = 1f, pitch: Float = 1f) {
        player.playSound(player.location, sound, SoundCategory.MASTER, volume, pitch)
    }

    // ── GUI ──
    fun open(p: Player) = play(p, Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.0f)
    fun click(p: Player) = play(p, Sound.UI_BUTTON_CLICK, 0.5f, 1.3f)

    // ── 결과 ──
    fun success(p: Player) = play(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
    fun fail(p: Player) = play(p, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
    fun purchase(p: Player) = play(p, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f)
    fun reward(p: Player) = play(p, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f)
    fun bigReward(p: Player) = play(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f)
    fun coin(p: Player) = play(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.4f)
    fun equip(p: Player) = play(p, Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.0f)

    // ── 알림 ──
    fun notify(p: Player) = play(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f)
    fun alert(p: Player) = play(p, Sound.BLOCK_BELL_RESONATE, 0.8f, 1.0f)
    fun cooldown(p: Player) = play(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f)
    fun chat(p: Player) = play(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.3f, 1.8f)

    // ── 여러 명에게 ──
    fun all(sound: Sound, volume: Float = 1f, pitch: Float = 1f) =
        Bukkit.getOnlinePlayers().forEach { play(it, sound, volume, pitch) }

    fun nation(name: String, action: (Player) -> Unit) =
        Nation.nations[name]?.members?.forEach { uuid -> Bukkit.getPlayer(uuid)?.let(action) }
}
