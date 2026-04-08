package dev.emin.fleetrichpresence.bridge

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.nio.file.Path

interface WindowContextProvider {
    fun capture(preferredPid: Long, logger: System.Logger): WindowContext?

    companion object {
        fun forCurrentPlatform(): WindowContextProvider {
            val osName = System.getProperty("os.name").orEmpty()
            return if (osName.contains("windows", ignoreCase = true)) {
                WindowsWindowContextProvider
            } else {
                NoopWindowContextProvider
            }
        }
    }
}

private object NoopWindowContextProvider : WindowContextProvider {
    override fun capture(preferredPid: Long, logger: System.Logger): WindowContext? = null
}

private object WindowsWindowContextProvider : WindowContextProvider {
    override fun capture(preferredPid: Long, logger: System.Logger): WindowContext? {
        return runCatching {
            captureForegroundFleetWindow(preferredPid)
                ?: captureBestVisibleFleetWindow(preferredPid)
        }.onFailure { error ->
            logger.log(System.Logger.Level.DEBUG, "Failed to capture Fleet window title.", error)
        }.getOrNull()
    }

    private fun captureForegroundFleetWindow(preferredPid: Long): WindowContext? {
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
        return hwnd.toWindowContext(preferredPid)
    }

    private fun captureBestVisibleFleetWindow(preferredPid: Long): WindowContext? {
        val candidates = mutableListOf<WindowContext>()
        User32.INSTANCE.EnumWindows(
            WinUser.WNDENUMPROC { hwnd, _ ->
                hwnd.toWindowContext(preferredPid)?.let(candidates::add)
                true
            },
            null,
        )

        return candidates.maxWithOrNull(
            compareBy<WindowContext>({ score(it) }, { it.rawTitle.length }),
        )
    }

    private fun HWND.toWindowContext(preferredPid: Long): WindowContext? {
        if (!User32.INSTANCE.IsWindowVisible(this)) {
            return null
        }

        val title = readWindowTitle(this) ?: return null
        val pid = readProcessId(this)
        if (preferredPid > 0L && pid != preferredPid) {
            return null
        }
        if (!looksLikeFleetWindow(pid, title)) {
            return null
        }

        return WindowContext.fromTitle(pid, title)
    }

    private fun score(context: WindowContext): Int {
        var score = 0
        if (!context.fileName.isNullOrBlank()) {
            score += 3
        }
        if (!context.projectName.isNullOrBlank()) {
            score += 2
        }
        if (!context.rawTitle.isBlank()) {
            score += 1
        }
        return score
    }

    private fun readWindowTitle(hwnd: HWND): String? {
        val buffer = CharArray(1024)
        val length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.size)
        if (length <= 0) {
            return null
        }

        return Native.toString(buffer)?.trim()?.takeIf(String::isNotBlank)
    }

    private fun readProcessId(hwnd: HWND): Long {
        val pidRef = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef)
        return Integer.toUnsignedLong(pidRef.value)
    }

    private fun looksLikeFleetWindow(pid: Long, title: String): Boolean {
        val command = ProcessHandle.of(pid)
            .flatMap { handle -> handle.info().command() }
            .orElse("")

        val executableName = if (command.isBlank()) {
            ""
        } else {
            runCatching { Path.of(command).fileName.toString() }.getOrDefault(command)
        }
        return executableName.equals("fleet.exe", ignoreCase = true) ||
            executableName.equals("fleet", ignoreCase = true) ||
            executableName.startsWith("fleet", ignoreCase = true) ||
            title.contains("JetBrains Fleet", ignoreCase = true)
    }
}
