package io.smartbox.phone

import android.content.Context
import io.smartbox.core.CameraRole
import io.smartbox.core.PairingInfo

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("smartbox", Context.MODE_PRIVATE)

    var pairing: PairingInfo?
        get() = sp.getString("pairing", null)?.let(PairingInfo::parse)
        set(v) = sp.edit().putString("pairing", v?.toUri()).apply()

    var role: CameraRole
        get() = CameraRole.parse(sp.getString("role", null))
        set(v) = sp.edit().putString("role", v.name).apply()
}
