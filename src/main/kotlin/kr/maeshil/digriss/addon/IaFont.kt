package kr.maeshil.digriss.addon

import dev.lone.itemsadder.api.FontImages.FontImageWrapper

// ItemsAdder 폰트 이미지로 GUI 제목 뒤에 배경 그림을 깔기
internal object IaFont {

    /** 배경 이미지 + 제목 위치를 맞춘 문자열. 그 이미지가 없으면 null */
    fun background(id: String, bgShift: Int, titleShift: Int): String? {
        val img = FontImageWrapper(id)
        if (!img.exists()) return null
        return FontImageWrapper.applyPixelsOffsetToString(img.string, bgShift) +
            FontImageWrapper.applyPixelsOffsetToString("", titleShift)
    }
}
