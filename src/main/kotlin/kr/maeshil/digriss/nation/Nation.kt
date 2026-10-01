package kr.maeshil.digriss.nation

object Nation {
    val nations = mutableMapOf<String, Nations>()
    val chunkClaims = mutableMapOf<String, String>() // "world,x,z" -> "국가이름"
}