package uz.perfecttap.core

import com.topjohnwu.superuser.Shell

/**
 * Thin wrapper around libsu so the rest of the app never deals with shell internals.
 * All methods block; call them off the main thread.
 */
class RootManager {

    /**
     * Starts (or reuses) the root shell, prompting for su access if needed.
     * `Shell.isAppGrantedRoot()` alone returns null until a shell exists, so it
     * cannot be used as the first check.
     */
    fun isGranted(): Boolean = try {
        Shell.getShell().isRoot
    } catch (_: Throwable) {
        false
    }

    fun exec(vararg commands: String): CommandResult {
        val result = try {
            Shell.cmd(*commands).exec()
        } catch (t: Throwable) {
            return CommandResult(
                success = false,
                code = -1,
                out = emptyList(),
                err = listOf(t.message ?: t.javaClass.simpleName)
            )
        }
        return CommandResult(
            success = result.isSuccess,
            code = result.code,
            out = result.out,
            err = result.err
        )
    }

    data class CommandResult(
        val success: Boolean,
        val code: Int,
        val out: List<String>,
        val err: List<String>
    ) {
        val stdout: String get() = out.joinToString("\n")
        val stderr: String get() = err.joinToString("\n")
    }
}
