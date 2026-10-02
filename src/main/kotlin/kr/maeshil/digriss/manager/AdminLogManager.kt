package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.command.CommandSender
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// 관리자 명령어 사용 기록 (plugins/Digriss/admin.log)
// 운영자도 같이 플레이하므로, 재화 지급 등을 누가 언제 했는지 남겨서 투명하게 공개할 수 있게 함
class AdminLogManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "admin.log")
    private val zone = ZoneId.of("Asia/Seoul")
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
    }

    // 콘솔에서 실행하면 관리자 이름이 CONSOLE로 남음
    fun log(sender: CommandSender, action: String) {
        val time = ZonedDateTime.now(zone).format(format)
        runCatching { file.appendText("$time | ${sender.name} | $action\n") }
            .onFailure { plugin.logger.severe("[관리 기록] admin.log 기록 실패: ${it.message}") }
    }
}
