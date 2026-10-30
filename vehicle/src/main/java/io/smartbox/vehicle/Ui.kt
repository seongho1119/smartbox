package io.smartbox.vehicle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 화면을 코드로 구성할 때 쓰는 작은 도우미 모음. 차량 화면에 맞게 글자를 크게 쓴다. */
object Ui {
    val BG = Color.rgb(15, 23, 32)
    val CARD = Color.rgb(28, 39, 52)
    val TEXT = Color.rgb(236, 241, 246)
    val MUTED = Color.rgb(150, 164, 178)
    val RED = Color.rgb(240, 90, 90)
    val ACCENT = Color.rgb(77, 163, 255)

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun text(ctx: Context, s: String = "", size: Float = 16f, color: Int = TEXT) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        minHeight = dp(ctx, 56)
        setOnClickListener { onClick() }
    }

    fun card(ctx: Context, title: String? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 14)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(CARD)
            cornerRadius = dp(ctx, 14).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 12) }
        if (title != null) addView(text(ctx, title, 14f, MUTED))
    }

    fun row(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun rotate(b: Bitmap, degrees: Int): Bitmap =
        if (degrees % 360 == 0) b
        else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}
