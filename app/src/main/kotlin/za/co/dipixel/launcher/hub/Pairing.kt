package za.co.dipixel.launcher.hub

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import kotlin.random.Random

/** A 4 digit code shown on the TV. A phone that enters it once gets a token and stays paired. */
object Pairing {
    private const val PREFS = "hub"

    fun code(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = p.getString("code", null)
        if (existing != null) return existing
        val c = Random.nextInt(1000, 10000).toString()
        p.edit().putString("code", c).apply()
        return c
    }

    fun newCode(context: Context): String {
        val c = Random.nextInt(1000, 10000).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("code", c).putStringSet("tokens", emptySet()).apply()
        return c
    }

    fun pair(context: Context, code: String): String? {
        if (code.trim() != code(context)) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = UUID.randomUUID().toString()
        val set = HashSet(p.getStringSet("tokens", emptySet()) ?: emptySet())
        set += token
        p.edit().putStringSet("tokens", set).apply()
        return token
    }

    fun valid(context: Context, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("tokens", emptySet()) ?: emptySet()
        return token in set
    }

    fun pairedCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("tokens", emptySet())?.size ?: 0

    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: "unknown"
    } catch (e: Exception) { "unknown" }
}
