package com.martin.ruby.ai.models

import java.net.URI
import java.util.Locale

/**
 * The AI models Ruby can use besides Claude and Free mode, without Android in it (easy to test):
 * on-phone GGUF models run with llama.cpp ([LocalModel]), and cloud or PC models that speak the
 * OpenAI-compatible chat API ([RemoteEndpoint]).
 */

/** A GGUF model file Ruby can download and run on the phone. */
data class LocalModel(
    val id: String,
    val name: String,
    val url: String,
    val fileName: String,
    /** The file's size in bytes, if known (presets); a link the user added learns it when downloading. */
    val bytes: Long? = null,
    /** Its SHA-256 (from Hugging Face), checked after downloading; null for links the user added. */
    val sha256: String? = null,
    /** Only in Martin's own build (see [ModelCatalog.localPresets]). */
    val personalOnly: Boolean = false,
    val note: String? = null,
    /** Added by the user (can be removed), not a preset. */
    val custom: Boolean = false,
)

/** A cloud service or a PC that answers OpenAI-compatible chat requests (OpenRouter, Ollama, LM Studio...). */
data class RemoteEndpoint(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    /** Whether it needs an API key (stored encrypted, apart from this). */
    val needsKey: Boolean,
)

object ModelCatalog {

    val GEMMA_4_E4B_UNCENSORED = LocalModel(
        id = "gemma-4-e4b-uncensored-q4km",
        name = "Gemma 4 E4B Uncensored Aggressive",
        url = "https://huggingface.co/HauhauCS/Gemma-4-E4B-Uncensored-HauhauCS-Aggressive/resolve/main/" +
            "Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf",
        fileName = "Gemma-4-E4B-Uncensored-HauhauCS-Aggressive-Q4_K_M.gguf",
        bytes = 5_335_285_728,
        sha256 = "d0027dd3a9128d9323e9f282c8bf010a8526c46477584535991dc1a869b56e96",
        personalOnly = true,
        note = "Uncensored: it doesn't refuse. Ruby's safety rules are still enforced by the phone.",
    )

    val QWEN_3_5_9B = LocalModel(
        id = "qwen3.5-9b-q4km",
        name = "Qwen 3.5 9B",
        url = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf",
        fileName = "Qwen3.5-9B-Q4_K_M.gguf",
        bytes = 5_680_522_464,
        sha256 = "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8",
    )

    /**
     * The pre-filled on-phone models. The uncensored one is only listed in Martin's own build
     * ([personalBuild]); a public build never shows it.
     */
    fun localPresets(personalBuild: Boolean): List<LocalModel> =
        listOf(GEMMA_4_E4B_UNCENSORED, QWEN_3_5_9B).filter { personalBuild || !it.personalOnly }

    const val PC_IP = "<PC IP>"

    /** The pre-filled cloud and PC entries; the user fills in a key or the PC's address. */
    val REMOTE_PRESETS = listOf(
        RemoteEndpoint("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", model = "", needsKey = true),
        RemoteEndpoint("pc-ollama", "My PC with Ollama", "http://$PC_IP:11434/v1", model = "", needsKey = false),
        RemoteEndpoint("pc-lmstudio", "My PC with LM Studio", "http://$PC_IP:1234/v1", model = "", needsKey = false),
    )

    /**
     * An on-phone model from a link the user pasted, or why it can't be one. The link must be
     * https and end in a .gguf file (Hugging Face "resolve" links do).
     */
    fun fromLink(link: String, name: String? = null): Result<LocalModel> {
        val url = link.trim()
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("That isn't a link."))
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("The link must start with https://."))
        }
        val file = uri.path.orEmpty().substringAfterLast('/')
        if (!file.lowercase(Locale.ROOT).endsWith(".gguf") || file.length <= 5) {
            return Result.failure(IllegalArgumentException("The link must point to a .gguf file."))
        }
        if (file.contains("..") || file.contains('\\')) return Result.failure(IllegalArgumentException("That file name won't do."))
        val shown = name?.trim()?.takeIf { it.isNotEmpty() } ?: file.removeSuffix(".gguf").removeSuffix(".GGUF")
        val id = "custom-" + file.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9.]+"), "-")
        return Result.success(LocalModel(id = id, name = shown, url = url, fileName = file, custom = true))
    }

    /** Whether [baseUrl] still has the "<PC IP>" placeholder, or isn't a usable http(s) address. */
    fun baseUrlProblem(baseUrl: String): String? {
        val url = baseUrl.trim()
        if (url.contains(PC_IP)) return "Put your PC's address in place of $PC_IP (for example its Tailscale or home Wi-Fi address)."
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "That isn't an address."
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return "The address must start with http:// or https://."
        return null
    }

    /** The chat completions address for [baseUrl] ("…/v1" becomes "…/v1/chat/completions"). */
    fun chatUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** The model list address for [baseUrl] (used by "Test connection"). */
    fun modelsUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/models"
}

/** Whether a model fits in the phone's memory. */
enum class RamFit { FITS, TIGHT, TOO_BIG }

object RamCheck {

    /** Room for the conversation (the context) and llama.cpp itself, on top of the model's weights. */
    const val OVERHEAD_BYTES = 1_200_000_000L

    /**
     * Whether a [modelBytes] model fits: it needs about its size plus [OVERHEAD_BYTES]. Android
     * and other apps need room too, so more than 75% of [totalRam] won't fit; more than what's
     * [availableRam] right now is tight (it may be slow, or Android may close other apps).
     */
    fun fit(modelBytes: Long, totalRam: Long, availableRam: Long): RamFit {
        val needs = modelBytes + OVERHEAD_BYTES
        return when {
            needs > totalRam * 3 / 4 -> RamFit.TOO_BIG
            needs > availableRam -> RamFit.TIGHT
            else -> RamFit.FITS
        }
    }

    fun warning(fit: RamFit, modelBytes: Long, totalRam: Long): String? = when (fit) {
        RamFit.FITS -> null
        RamFit.TIGHT -> "This model needs about ${gb(modelBytes + OVERHEAD_BYTES)} of memory, more than is free right now. " +
            "It may load slowly, and Android may close other apps."
        RamFit.TOO_BIG -> "This model needs about ${gb(modelBytes + OVERHEAD_BYTES)} of memory, too much for this phone " +
            "(${gb(totalRam)} in all). Pick a smaller one."
    }

    /** "5.3 GB" (decimal gigabytes, as phones and Hugging Face show sizes). */
    fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}
