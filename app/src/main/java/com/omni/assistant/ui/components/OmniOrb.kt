package com.omni.assistant.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import com.omni.assistant.data.AgentStatus
import com.omni.assistant.ui.theme.OmniColors
import kotlin.math.*

/**
 * OmniOrb — DS 2.0 plasma orb.
 *
 * On Android 13+ (API 33) this is the canonical WebGL shader from the Figma
 * plugin (`figma-plugin/ui.html`) ported to AGSL (RuntimeShader). It draws a
 * deep-navy glass sphere with a hyperspace star field inside, liquid-glass
 * surface distortion, dynamic rim fresnel, and per-state hue/sat modulation.
 *
 * On older devices the shader isn't available, so we fall back to a simpler
 * canvas-drawn navy orb that still reads correctly but without animation.
 */
enum class OmniOrbPerformance {
    Static,
    Efficient,
    Full,
}

@Composable
fun OmniOrb(
    status: AgentStatus,
    modifier: Modifier = Modifier,
    performance: OmniOrbPerformance = OmniOrbPerformance.Efficient,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ShaderOrb(status = status, modifier = modifier, performance = performance)
    } else {
        FallbackOrb(status = status, modifier = modifier, performance = performance)
    }
}

// ─── State index shared by shader + fallback ────────────────────────────────

private fun AgentStatus.toStateIndex(): Int = when (this) {
    is AgentStatus.Idle -> 0
    is AgentStatus.WakeWordListening, is AgentStatus.VoiceListening -> 1
    is AgentStatus.Processing -> 2
    is AgentStatus.Speaking -> 3
    is AgentStatus.Executing -> 4
    is AgentStatus.Done -> if (success) 5 else 6
    is AgentStatus.Error -> 6
}

private fun targetFrameMillis(status: AgentStatus, performance: OmniOrbPerformance): Long? {
    if (performance == OmniOrbPerformance.Static) return null

    val targetFps = when (performance) {
        OmniOrbPerformance.Static -> 0
        OmniOrbPerformance.Efficient -> when (status) {
            is AgentStatus.Idle -> 12
            is AgentStatus.Done,
            is AgentStatus.Error -> 8
            else -> 24
        }
        OmniOrbPerformance.Full -> when (status) {
            is AgentStatus.Idle -> 24
            else -> 30
        }
    }

    return if (targetFps <= 0) null else 1_000L / targetFps
}

// ─── Shader orb (API 33+) ───────────────────────────────────────────────────

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderOrb(status: AgentStatus, modifier: Modifier, performance: OmniOrbPerformance) {
    val shader = remember { RuntimeShader(OMNI_SHADER_SRC) }
    val brush = remember(shader) { ShaderBrush(shader) }

    // Track window focus — pause the frame loop during recents / snapshot
    // transitions so the shader's GPU work doesn't compete with the system
    // animation. Android sends onWindowFocusChanged(false) when Overview opens.
    val view = LocalView.current
    var hasWindowFocus by remember { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            hasWindowFocus = focused
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    val targetFrameMillis = targetFrameMillis(status, performance)

    // Drive time from the frame clock, but cap redraws aggressively. The shader
    // is fragment-heavy, and updating Compose state every vsync can contend
    // with IME, notification shade, and overlay animations on mid-tier devices.
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(hasWindowFocus, targetFrameMillis) {
        if (!hasWindowFocus || targetFrameMillis == null) return@LaunchedEffect
        val start = withFrameNanos { it } - (timeSec * 1_000_000_000f).toLong()
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrameNanos == 0L || now - lastFrameNanos >= targetFrameMillis * 1_000_000L) {
                    lastFrameNanos = now
                    timeSec = (now - start) / 1_000_000_000f
                }
            }
        }
    }

    val state = status.toStateIndex()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            shader.setFloatUniform("uTime", timeSec)
            shader.setIntUniform("uState", state)
            shader.setFloatUniform("uAmp", 1.0f)
            shader.setFloatUniform("uRes", size.width, size.height)
            drawRect(brush = brush, size = size)
        }
    }
}

// ─── Fallback orb (API < 33) ────────────────────────────────────────────────

@Composable
private fun FallbackOrb(status: AgentStatus, modifier: Modifier, performance: OmniOrbPerformance) {
    val shouldAnimate = performance != OmniOrbPerformance.Static
    val breathe: Float
    val rotation: Float
    if (shouldAnimate) {
        val t = rememberInfiniteTransition(label = "orb-fb")
        val animatedBreathe by t.animateFloat(
            0.96f, 1.04f,
            animationSpec = infiniteRepeatable(
                tween(2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathe"
        )
        val animatedRotation by t.animateFloat(
            0f, 360f,
            animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
            label = "rot"
        )
        breathe = animatedBreathe
        rotation = animatedRotation
    } else {
        breathe = 1f
        rotation = 0f
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension / 2 * 0.72f * breathe

            val tint = when (status) {
                is AgentStatus.Done -> if (status.success) OmniColors.Success else OmniColors.Error
                is AgentStatus.Error -> OmniColors.Error
                else -> null
            }
            if (tint != null) drawSolidOrb(cx, cy, r, tint)
            else drawNavyOrb(cx, cy, r)

            when (status) {
                is AgentStatus.Processing, is AgentStatus.Executing ->
                    rotate(rotation, pivot = Offset(cx, cy)) {
                        drawDashedRing(
                            Offset(cx, cy),
                            r * 1.18f,
                            Color(0xFF6CB8FF).copy(alpha = 0.7f),
                            2.2f, 28f, 18f,
                        )
                    }
                is AgentStatus.WakeWordListening,
                is AgentStatus.VoiceListening -> drawRings(cx, cy, r)
                else -> Unit
            }
        }
    }
}

private fun DrawScope.drawNavyOrb(cx: Float, cy: Float, r: Float) {
    // Deep-navy body — matches shader palette cDeep / cMid.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF2F5FB8),
                Color(0xFF0E1C3A),
                Color(0xFF05070E),
            ),
            center = Offset(cx, cy),
            radius = r * 1.2f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    // Fresnel rim
    drawCircle(
        color = Color(0xFF7FC0FF).copy(alpha = 0.35f),
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = r * 0.06f),
    )
    // Specular
    val gCx = cx - r * 0.3f
    val gCy = cy - r * 0.5f
    scale(1f, 0.75f, pivot = Offset(gCx, gCy)) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.55f),
                    Color.Transparent,
                ),
                center = Offset(gCx, gCy),
                radius = r * 0.4f,
            ),
            radius = r * 0.4f,
            center = Offset(gCx, gCy),
        )
    }
}

private fun DrawScope.drawSolidOrb(cx: Float, cy: Float, r: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.45f), Color.Transparent),
            center = Offset(cx, cy),
            radius = r * 2.0f,
        ),
        radius = r * 2.0f,
        center = Offset(cx, cy),
    )
    drawCircle(color = color, radius = r, center = Offset(cx, cy))
}

private fun DrawScope.drawRings(cx: Float, cy: Float, r: Float) {
    drawCircle(
        color = Color(0xFF6CB8FF).copy(alpha = 0.55f),
        radius = r * 1.15f,
        center = Offset(cx, cy),
        style = Stroke(width = 2.5f),
    )
    drawCircle(
        color = Color(0xFF6CB8FF).copy(alpha = 0.25f),
        radius = r * 1.32f,
        center = Offset(cx, cy),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawDashedRing(
    center: Offset,
    radius: Float,
    color: Color,
    strokeWidth: Float,
    dashLength: Float,
    gapLength: Float,
) {
    val circumference = 2 * PI * radius
    val count = (circumference / (dashLength + gapLength)).toInt().coerceAtLeast(8)
    val anglePer = (2 * PI / count).toFloat()
    for (i in 0 until count) {
        val a0 = i * anglePer
        val a1 = a0 + anglePer * (dashLength / (dashLength + gapLength))
        drawLine(
            color = color,
            start = Offset(center.x + radius * cos(a0), center.y + radius * sin(a0)),
            end = Offset(center.x + radius * cos(a1), center.y + radius * sin(a1)),
            strokeWidth = strokeWidth,
        )
    }
}

@Suppress("unused")
private fun Size.dbg(): String = "${width}x${height}"

// ─── AGSL shader — port of figma-plugin/ui.html FS, reduced star count ──────
//
// Keep names short in the shader to minimise compile cost. 72 stars instead
// of 320 from the bake-tool keeps the live orb responsive beside IME and
// notification-shade animations on mid-tier mobile GPUs.
//
// State uniform (int):  0=Idle 1=Listening 2=Thinking 3=Speaking 4=Executing
//                       5=Success 6=Error 7=Muted
private const val OMNI_SHADER_SRC = """
uniform float uTime;
uniform int   uState;
uniform float uAmp;
uniform float2 uRes;

float hashP(float2 p){ return fract(sin(dot(p, float2(127.1,311.7))) * 43758.5453); }
float nse(float2 p){
  float2 i = floor(p); float2 f = fract(p);
  float2 u = f*f*(3.0-2.0*f);
  return mix(mix(hashP(i+float2(0,0)), hashP(i+float2(1,0)), u.x),
             mix(hashP(i+float2(0,1)), hashP(i+float2(1,1)), u.x), u.y);
}
float fbm2f(float2 p){ float v=0.0; float a=0.5; for(int k=0;k<2;k++){v+=a*nse(p);p*=2.03;a*=0.5;} return v; }

float4 sParams(int s, float t){
  float speed = 1.0, ampO = 1.0, hue = 0.0, sat = 1.0;
  if (s == 0){ speed = 0.55; ampO = 0.88 + 0.12*sin(t*1.05); }
  else if (s == 1){ speed = 1.35; ampO = 1.15 + 0.25*abs(sin(t*5.5)); hue = -0.10; }
  else if (s == 2){ speed = 1.00; ampO = 0.95; hue = 0.04; }
  else if (s == 3){ speed = 1.20; ampO = 1.00 + 0.20*sin(t*8.0); hue = -0.04; }
  else if (s == 4){ speed = 1.05; ampO = 1.00 + 0.15*sin(t*6.0); hue = 0.03; sat = 1.00; }
  else if (s == 5){ speed = 1.10; ampO = 0.90 + 0.20*smoothstep(0.0, 0.6, sin(t*0.8)); hue = 0.30; sat = 0.95; }
  else if (s == 6){ speed = 0.70; ampO = 0.70; hue = 0.60; sat = 1.00; }
  else { speed = 0.30; ampO = 0.55; hue = 0.00; sat = 0.15; }
  return float4(speed, ampO, hue, sat);
}

float3 hueRot(float3 c, float h){
  // Rotate hue in YIQ space.
  float3 yiq = float3(
    dot(c, float3(0.299, 0.587, 0.114)),
    dot(c, float3(0.596,-0.274,-0.322)),
    dot(c, float3(0.211,-0.523, 0.312))
  );
  float a = h * 6.2831853;
  float ca = cos(a), sa = sin(a);
  float y = yiq.x;
  float ii = yiq.y * ca - yiq.z * sa;
  float q  = yiq.y * sa + yiq.z * ca;
  return float3(
    y * 1.0 + ii *  0.956 + q *  0.621,
    y * 1.0 + ii * -0.272 + q * -0.647,
    y * 1.0 + ii * -1.106 + q *  1.703
  );
}

float3 stars(float2 p, float t, float ampI){
  float3 acc = float3(0.0);
  float gSpeed = 0.085;
  for (int i = 0; i < 72; i++){
    float fi = float(i);
    float s1 = hashP(float2(fi,  1.73));
    float s2 = hashP(float2(fi,  4.21));
    float s3 = hashP(float2(fi,  7.57));
    float s4 = hashP(float2(fi,  9.11));
    float s5 = hashP(float2(fi, 11.37));
    float s6 = hashP(float2(fi, 23.11));
    float s3b= hashP(float2(fi, 19.47));
    float tier = hashP(float2(fi, 31.7));
    float travelDir = (s5 < 0.40) ? -1.0 : 1.0;
    float vanishRot = -t * 0.06;
    float ang = fi * 2.39996323 + (s1 - 0.5) * 0.40 + vanishRot;
    float spdJ = mix(0.08, 1.6, pow(s3, 1.6)) * mix(0.55, 1.3, s3b);
    float spd = gSpeed * spdJ;
    float age = fract(s2 + t * spd);
    float closeness = (travelDir > 0.0) ? age : (1.0 - age);
    float maxR = mix(0.15, 1.45, pow(s6, 3.5));
    float r = pow(closeness, 1.7) * maxR;
    float2 dir = float2(cos(ang), sin(ang));
    float2 pos = dir * r;
    if (r > 1.6) { continue; }
    float sizeF = 0.75;
    float brightF = 1.40;
    if (tier >= 0.5 && tier < 0.8333) { sizeF = 2.00; brightF = 2.30; }
    else if (tier >= 0.8333) { sizeF = 4.50; brightF = 3.60; }
    float distF = maxR / 1.45;
    float brightDF = mix(0.55, 1.0, distF);
    float baseSize = mix(0.0020, 0.040, pow(closeness, 2.2)) * sizeF * distF;
    float brightness = pow(closeness, 2.0) * brightF * brightDF * ampI;
    float birthFade = smoothstep(0.02, 0.18, age);
    float deathFade = 1.0 - smoothstep(0.85, 1.00, age);
    brightness *= birthFade * deathFade;
    float2 d = p - pos;
    float dd = dot(d, d);
    float influenceR2 = baseSize * baseSize * 18.0;
    if (dd > influenceR2 && closeness < 0.72) { continue; }
    float head     = exp(-dd / (baseSize*baseSize * 1.35 + 1e-6));
    float headMid  = exp(-dd / (baseSize*baseSize * 0.85 + 1e-6));
    float headCore = exp(-dd / (baseSize*baseSize * 0.30 + 1e-6));
    float along = dot(d, dir);
    float perp  = dot(d, float2(-dir.y, dir.x));
    float effAlong = along * travelDir;
    float streakLen = mix(0.0, 0.22, pow(closeness, 1.5)) * mix(0.6, 1.3, s4);
    float streakW   = baseSize * 0.48;
    float streak = 0.0;
    if (effAlong < 0.0 && streakLen > 0.0){
      streak = exp(effAlong / streakLen) * exp(-(perp*perp) / (streakW*streakW + 1e-6));
    }
    float halo = exp(-dd / (baseSize*baseSize * 14.0 + 1e-6)) * pow(closeness, 2.0) * 0.30;
    float3 colFringe = float3(0.38, 0.66, 1.00);
    float3 colCore   = mix(float3(0.80, 0.92, 1.00), float3(0.97, 0.99, 1.00), closeness);
    float3 colMid    = mix(colFringe, colCore, 0.55);
    float3 colStreak = float3(0.48, 0.78, 1.00);
    float3 colHalo   = float3(0.32, 0.62, 1.00);
    acc += colFringe * head     * brightness * 0.85;
    acc += colMid    * headMid  * brightness * 1.15;
    acc += colCore   * headCore * brightness * 1.30;
    acc += colStreak * streak   * brightness * 0.75;
    acc += colHalo   * halo     * brightness;
    float rimRadius = length(p);
    float rimProx = smoothstep(0.62, 0.96, rimRadius) * (1.0 - smoothstep(0.98, 1.10, rimRadius));
    if (rimProx > 0.0 && travelDir > 0.0){
      float2 np = (rimRadius > 1e-4) ? p / rimRadius : float2(0.0);
      float angCos = max(0.0, dot(np, dir));
      float arc = pow(angCos, 22.0);
      float atRim = smoothstep(0.55, 0.96, closeness);
      float splash = rimProx * arc * atRim * brightness;
      float3 rimA = mix(float3(0.55, 0.82, 1.00), float3(0.92, 0.98, 1.00), closeness);
      float3 rimB = mix(float3(0.70, 0.88, 1.00), float3(0.96, 0.99, 1.00), closeness);
      acc += rimA * splash * 1.1;
      acc += rimB * pow(arc, 2.0) * rimProx * atRim * brightness * 1.3;
    }
  }
  return acc;
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / uRes;
  float2 p = uv * 2.0 - 1.0;
  // Keep the orb square even if drawn on a non-square surface. We scale p so
  // the shorter axis spans -1..1 and the longer axis is letterboxed symmetrically.
  float ar = uRes.x / uRes.y;
  if (ar > 1.0) { p.x *= ar; }
  else          { p.y /= ar; }

  float4 sp4 = sParams(uState, uTime);
  float tSpeed = sp4.x;
  float ampS = sp4.y * uAmp;
  float hue = sp4.z;
  float sat = sp4.w;
  float t = uTime * tSpeed;

  float3 cDeep = float3(0.055, 0.110, 0.227);
  float3 cMid  = float3(0.184, 0.373, 0.721);
  float3 cRim  = float3(0.498, 0.753, 1.000);

  float sR = 0.60;
  float dist = length(p);
  float sMask = smoothstep(sR+0.003, sR-0.003, dist);
  float2 sp = p / sR;
  float sd = length(sp);
  float sz = sqrt(max(0.0, 1.0 - sd*sd));
  float3 N = float3(sp, sz);

  float2 nwp = sp * 1.6;
  float2 flow1 = float2(fbm2f(nwp + float2(0.0,  t*0.05)) - 0.5,
                        fbm2f(nwp + float2(7.3, -t*0.04)) - 0.5);
  float2 flow2 = float2(fbm2f(nwp*1.7 + flow1 + float2( 3.1,  t*0.035)) - 0.5,
                        fbm2f(nwp*1.7 + flow1 + float2(-2.7, -t*0.030)) - 0.5);
  float2 nwarp = (flow1 + 0.35 * flow2) * 0.45;
  float edgeW = pow(1.0 - sz, 1.5);
  float distortAmp = mix(0.05, 0.85, edgeW);
  float3 Nlq = normalize(N + float3(nwarp * distortAmp, 0.0));

  float3 viewDir = float3(0.0, 0.0, -1.0);
  float3 Rr = refract(viewDir, Nlq, 1.0/1.52);
  float backDepth = 1.15;
  float2 spRaw = sp + Rr.xy * (backDepth / max(-Rr.z, 0.18));
  float lens = 1.0 - pow(sd, 2.4) * 0.55;
  float2 spC = spRaw * 0.78 * lens;
  float rC = length(spC);

  float3 interior = cDeep * 1.25;
  interior += mix(cDeep, cMid, 0.5) * (1.0 - smoothstep(0.0, 1.05, rC)) * 0.35;
  interior += stars(spC, t, ampS);
  interior *= mix(0.65, 1.0, pow(sz, 0.55));

  float rimBand = smoothstep(0.88, 1.0, sd);
  interior *= 1.0 - rimBand * 0.45;

  float fres = pow(1.0 - sz, 3.0);
  float bInt = dot(interior, float3(0.33));
  interior += cRim * fres * (0.15 + bInt*0.4);

  float3 Lshade = normalize(float3(0.35, -0.55, 0.4));
  float shade = pow(max(0.0, dot(N, Lshade)), 2.0);
  interior *= mix(1.0, 0.82, shade * 0.45);

  float sparkle = 0.0;
  for (int k = 0; k < 3; k++){
    float fk = float(k);
    float2 cell = sp * (5.0 + fk*3.0);
    float2 ci = floor(cell);
    float2 cf = fract(cell) - 0.5;
    float h = hashP(ci + fk*37.0);
    if (h > 0.94){
      float2 off = float2(hashP(ci+float2(1.0,0.0))-0.5, hashP(ci+float2(0.0,1.0))-0.5) * 0.6;
      float d = length(cf - off);
      float tw = 0.5 + 0.5 * sin(t*(1.5 + h*3.0) + h*20.0);
      sparkle += exp(-d*d * 900.0) * tw;
    }
  }
  interior += float3(1.0) * sparkle * smoothstep(0.1, 0.55, sz) * 0.9;

  interior = hueRot(interior, hue);
  float luma = dot(interior, float3(0.2126, 0.7152, 0.0722));
  interior = mix(float3(luma), interior, sat);

  float3 color = interior * sMask;
  return half4(half3(color), half(sMask));
}
"""
