#!/usr/bin/env python3
"""
Makes the "Rock Heavy" drum kit of Burger Studio: every sound is synthesized here from
sine waves, noise and filters, so the kit is our own and free of any sample licence.

Run:  python3 tools/make_rock_kit.py
Out:  src/main/assets/kits/rock/*.wav  (44.1 kHz, mono, 16 bit)

Needs numpy.
"""
import math
import os
import wave

import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "assets", "kits", "rock")
rng = np.random.default_rng(1234)


# ---------------- building blocks ----------------

def t_axis(secs):
    return np.arange(int(secs * SR)) / SR


def sine_sweep(t, f_start, f_end, tau):
    """A sine whose pitch falls from f_start to f_end with time constant tau (a drum head)."""
    f = f_end + (f_start - f_end) * np.exp(-t / tau)
    phase = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(phase)


def noise(n):
    return rng.uniform(-1, 1, n)


def onepole_lp(x, hz):
    a = 1 - math.exp(-2 * math.pi * hz / SR)
    y = np.empty_like(x)
    s = 0.0
    for i, v in enumerate(x):
        s += (v - s) * a
        y[i] = s
    return y


def onepole_hp(x, hz):
    return x - onepole_lp(x, hz)


def biquad(x, kind, hz, q=0.707, gain_db=0.0):
    """RBJ cookbook biquad: 'bp', 'lp', 'hp', 'peak'."""
    w0 = 2 * math.pi * hz / SR
    alpha = math.sin(w0) / (2 * q)
    cw = math.cos(w0)
    A = 10 ** (gain_db / 40)
    if kind == "lp":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "hp":
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]; a = [1 + alpha, -2 * cw, 1 - alpha]
    elif kind == "bp":
        b = [alpha, 0, -alpha]; a = [1 + alpha, -2 * cw, 1 - alpha]
    else:  # peak
        b = [1 + alpha * A, -2 * cw, 1 - alpha * A]; a = [1 + alpha / A, -2 * cw, 1 - alpha / A]
    b = [v / a[0] for v in b]
    a1, a2 = a[1] / a[0], a[2] / a[0]
    y = np.empty_like(x)
    x1 = x2 = y1 = y2 = 0.0
    b0, b1, b2 = b
    for i, v in enumerate(x):
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, v
        y2, y1 = y1, o
        y[i] = o
    return y


def metal(t, freqs, duty=0.5):
    """Inharmonic square waves: the shimmer of cymbals and hats."""
    s = np.zeros_like(t)
    for f in freqs:
        s += np.where((t * f) % 1.0 < duty, 1.0, -1.0)
    return s / len(freqs)


def sat(x, drive):
    """Soft saturation: thicker and louder without hard clipping."""
    return np.tanh(x * drive) / math.tanh(drive)


def room(x, amount=0.18):
    """A few early reflections: the sound of a drum in a real room."""
    y = x.copy()
    for ms, g in ((11, 0.5), (17, 0.38), (23, 0.3), (31, 0.22), (43, 0.15)):
        d = int(ms * SR / 1000)
        y[d:] += x[:-d] * g * amount * 2
    return y


def finish(x, peak=0.94, fade_ms=8):
    x = x - np.mean(x[: min(len(x), 2000)]) * 0  # keep the attack untouched
    m = np.max(np.abs(x)) or 1.0
    x = x / m * peak
    f = int(fade_ms * SR / 1000)
    if f > 0:
        x[-f:] *= np.linspace(1, 0, f)
    return x


def write(name, x):
    os.makedirs(OUT, exist_ok=True)
    data = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data)
    print(f"{name:14s} {len(x) / SR:4.2f} s")


# ---------------- the kit ----------------

def kick_heavy():
    t = t_axis(0.62)
    body = sine_sweep(t, 150, 47, 0.032) * (np.exp(-t / 0.33) * np.minimum(1, t / 0.001))
    sub = np.sin(2 * np.pi * 47 * t) * np.exp(-t / 0.42) * 0.45 * np.minimum(1, t / 0.012)
    click = onepole_hp(noise(len(t)), 3500) * np.exp(-t / 0.0025) * 1.2
    beater = np.sin(2 * np.pi * 2400 * t) * np.exp(-t / 0.004) * 0.5
    x = body + sub + click + beater
    return finish(sat(x * 1.1, 2.6))


def kick_tight():
    t = t_axis(0.36)
    body = sine_sweep(t, 190, 56, 0.018) * np.exp(-t / 0.15)
    click = biquad(noise(len(t)), "bp", 3200, 1.2) * np.exp(-t / 0.003) * 3.0
    beater = np.sin(2 * np.pi * 1800 * t) * np.exp(-t / 0.006) * 0.8
    x = body + click + beater
    return finish(sat(x, 3.2))


def snare_heavy():
    t = t_axis(0.72)
    head = (sine_sweep(t, 220, 182, 0.02) + 0.6 * sine_sweep(t, 360, 330, 0.02)) * np.exp(-t / 0.085)
    wires = biquad(noise(len(t)), "bp", 3800, 0.6) * np.exp(-t / 0.17) * 1.4
    wires += onepole_hp(noise(len(t)), 6000) * np.exp(-t / 0.06) * 0.5
    crack = noise(len(t)) * np.exp(-t / 0.0018) * 1.5
    x = head * 1.1 + wires + crack
    x = sat(x, 1.9)
    return finish(room(x, 0.22))


def snare_crack():
    t = t_axis(0.5)
    head = (sine_sweep(t, 300, 240, 0.012) + 0.5 * sine_sweep(t, 520, 480, 0.012)) * np.exp(-t / 0.05)
    wires = biquad(noise(len(t)), "bp", 5200, 0.8) * np.exp(-t / 0.11) * 1.2
    crack = biquad(noise(len(t)), "bp", 2500, 2.0) * np.exp(-t / 0.004) * 4.0
    x = sat(head + wires + crack, 2.4)
    return finish(room(x, 0.15))


def hat(secs, tau):
    t = t_axis(secs)
    m = metal(t, [317, 421, 541, 663, 797, 941], 0.5)
    x = onepole_hp(m * 0.8 + noise(len(t)) * 0.6, 7000)
    x = biquad(x, "peak", 10000, 1.0, 4)
    env = np.exp(-t / tau) * np.minimum(1, t / 0.0007)
    return finish(x * env, 0.8)


def tom(f_start, f_end, secs, tau):
    t = t_axis(secs)
    body = sine_sweep(t, f_start, f_end, 0.06) * np.exp(-t / tau)
    over = sine_sweep(t, f_start * 1.6, f_end * 1.55, 0.05) * np.exp(-t / (tau * 0.5)) * 0.35
    stick = biquad(noise(len(t)), "bp", 1800, 1.0) * np.exp(-t / 0.006) * 1.2
    x = sat(body + over + stick, 1.8)
    return finish(room(x, 0.2))


def crash(secs=2.6, tau=0.95, trash=0.0):
    t = t_axis(secs)
    m = metal(t, [233, 347, 461, 587, 709, 853, 977, 1123], 0.45)
    n = noise(len(t))
    bright = onepole_hp(n * 0.8 + m * 0.6, 2500)
    dark = biquad(n * 0.5 + m * 0.5, "bp", 1200, 0.7)
    fade = np.exp(-t / (tau * 0.45))          # the bright part fades first
    x = bright * (0.4 + 0.6 * fade) + dark * 0.5
    if trash > 0:
        x = sat(x * (1 + trash * 3) * (1 + 0.6 * np.sin(2 * np.pi * 410 * t)), 1 + trash * 2)
    env = np.exp(-t / tau) * np.minimum(1, t / 0.004)
    return finish(x * env, 0.85, 30)


def ride():
    t = t_axis(2.2)
    bell = sum(np.sin(2 * np.pi * f * t) * g for f, g in ((520, 1.0), (781, 0.6), (1173, 0.45), (1840, 0.25)))
    ping = biquad(noise(len(t)), "bp", 6000, 1.2) * np.exp(-t / 0.02)
    wash = onepole_hp(noise(len(t)) + metal(t, [401, 557, 733, 911], 0.5), 4500) * 0.35
    x = bell * np.exp(-t / 0.7) * 0.5 + ping + wash * np.exp(-t / 1.0)
    return finish(x * np.minimum(1, t / 0.001), 0.8, 30)


KIT = [
    # the 8 pads, in pad order
    ("Rock Kick", kick_heavy),
    ("Rock Snare", snare_heavy),
    ("Rock Hat Closed", lambda: hat(0.14, 0.028)),
    ("Rock Hat Open", lambda: hat(0.7, 0.22)),
    ("Rock Tom High", lambda: tom(175, 140, 0.7, 0.24)),
    ("Rock Tom Floor", lambda: tom(98, 74, 1.0, 0.38)),
    ("Rock Crash", crash),
    ("Rock China", lambda: crash(1.9, 0.62, 0.8)),
    # extras for the library
    ("Rock Kick Tight", kick_tight),
    ("Rock Snare Crack", snare_crack),
    ("Rock Tom Mid", lambda: tom(130, 104, 0.85, 0.3)),
    ("Rock Ride", ride),
]

if __name__ == "__main__":
    for name, fn in KIT:
        write(name, fn())
