package com.aisub.player.tts

import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.Role
import com.aisub.player.translate.TranslationPrompt
import com.aisub.player.translate.Translator
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用大模型根据台词内容判断每句是谁说的：称呼、语气、对话轮次、自我介绍等。
 * 字幕分批发送，已识别的角色表随每批一起发出去，保证前后批次的角色 id 一致。
 * 只看文字、不听声音，独白或连续几人对话时可能分错，可以在角色列表里手动调整配音。
 */
object RoleDetector {

    private const val BATCH = 120

    private val SYSTEM = """
        你是影视对白分析助手。根据台词内容、称呼、语气和对话轮次，判断每句台词是哪个角色说的。
        要求：
        - roles 里已有的角色沿用原 id；出现新角色时新增，id 按 r1、r2、r3… 顺延；
        - name 尽量用剧中的名字；不知道名字时用简短描述，如「男主角」「店员」「旁白」；
        - gender 只能是 male、female、unknown，可以从称呼、自称、他人对其的称呼推断；
        - 实在判断不了的台词 role 填 null，不要乱猜；
        - 同一个人始终用同一个 id；
        - 只输出 JSON：{"roles":[{"id":"r1","name":"名字","gender":"male"}],"lines":[{"id":12,"role":"r1"}]}，不要输出其他内容。
    """.trimIndent()

    /**
     * 返回角色表和每句字幕的角色 id（字幕 id → 角色 id）。
     * known 是之前识别过的角色（保留用户选过的配音）。
     */
    suspend fun detect(
        translator: Translator,
        cues: List<Cue>,
        known: List<Role>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<List<Role>, Map<Int, String>> {
        val roles = LinkedHashMap<String, Role>()
        known.forEach { roles[it.id] = it }
        val assigned = HashMap<Int, String>()
        val batches = cues.chunked(BATCH)
        batches.forEachIndexed { i, batch ->
            onProgress(i, batches.size)
            val input = JSONObject()
                .put("roles", JSONArray(roles.values.map { JSONObject().put("id", it.id).put("name", it.name).put("gender", it.gender) }))
                .put("lines", JSONArray(batch.map { JSONObject().put("id", it.id).put("text", it.text) }))
            val json = TranslationPrompt.extractJson(translator.chat(SYSTEM, input.toString()))
            json.optJSONArray("roles")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val id = o.optString("id").ifEmpty { continue }
                    val gender = o.optString("gender").lowercase().let { if (it == "male" || it == "female") it else "unknown" }
                    val old = roles[id]
                    // 名字和性别以最新判断为准，配音沿用
                    roles[id] = (old ?: Role(id, id)).copy(name = o.optString("name").ifEmpty { old?.name ?: id }, gender = gender)
                }
            }
            json.optJSONArray("lines")?.let { arr ->
                val ids = batch.map { it.id }.toSet()
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val id = o.optInt("id", -1)
                    val role = o.optString("role").takeIf { !o.isNull("role") && it.isNotEmpty() } ?: continue
                    if (id in ids && role in roles) assigned[id] = role
                }
            }
        }
        onProgress(batches.size, batches.size)
        // 只保留真正说过话的角色
        val used = assigned.values.toSet()
        return roles.values.filter { it.id in used } to assigned
    }

    /**
     * 按性别给还没配音的角色分配不同的语音，尽量不重复；语音不够时循环使用。
     * 已经选过配音（同一引擎）的角色保持不变。
     */
    fun autoAssign(roles: List<Role>, voices: List<TtsVoice>, engine: String): List<Role> {
        if (voices.isEmpty()) return roles
        val taken = roles.filter { it.voiceEngine == engine && it.voice != null }.mapNotNull { it.voice }.toMutableSet()
        val byGender = voices.groupBy { it.gender }
        val cursor = HashMap<String, Int>()
        fun next(gender: String): String {
            val pool = byGender[gender].takeIf { !it.isNullOrEmpty() } ?: voices
            val fresh = pool.firstOrNull { it.id !in taken }
            if (fresh != null) return fresh.id
            val i = cursor.getOrDefault(gender, 0)
            cursor[gender] = i + 1
            return pool[i % pool.size].id
        }
        return roles.map { r ->
            if (r.voiceEngine == engine && r.voice != null) r
            else next(r.gender).let { v -> taken += v; r.copy(voice = v, voiceEngine = engine) }
        }
    }
}
