#!/usr/bin/env python3
"""
Makes the "MakerHip FM" sound bank: 32 original 6-operator FM voices (our own, free of any licence),
- rendered to WAV for the Sampler:   src/main/assets/fmbank/*.wav
- and as a DX7 / FM-1 bank:          src/main/assets/fmbank/MakerHip_FM.syx (load it into an FM-1 too)

Run: python3 tools/make_fm_bank.py   (needs numpy; uses tools/fm6.py)
"""
import os
import wave

import numpy as np

import fm6

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "assets", "fmbank")


def op(R, L, OL, coarse=1, fine=0, det=7, mode=0, rs=1, kvs=2, bp=39, ld=0, rd=0, lc=0, rc=0):
    return list(R) + list(L) + [bp, ld, rd, lc, rc, rs, 0, kvs, OL, mode, coarse, fine, det]


def voice(name, alg, fb, ops, transpose=24):
    """ops are OP1 .. OP6 (they are stored OP6 first)."""
    v = []
    for o in reversed(ops):
        v += o
    v += [99, 99, 99, 99, 50, 50, 50, 50, alg - 1, fb, 1, 35, 0, 0, 0, 1, 0, 3, transpose]
    v += list(name.upper().ljust(10)[:10].encode())
    assert len(v) == 155
    return v


# envelope helpers: (rates, levels)
def perc(d1=50, d2=35, r=60, l2=60):
    return (99, d1, d2, r), (99, l2, 0, 0)


def sus(a=95, d=50, s=88, r=55):
    return (a, d, 40, r), (99, 94, s, 0)


def fast(d=80):
    return (99, d, 40, 70), (99, 30, 0, 0)


def P(ratio_c, ol, env, fine=0, det=7, mode=0):
    R, L = env
    return op(R, L, ol, ratio_c, fine, det, mode)


off = op((99, 99, 99, 99), (0, 0, 0, 0), 0)          # a silent operator

BANK = [
    # name, base note, hold s, tail s, voice
    ("E.Piano Tine", 60, 0.6, 1.6, voice("EP TINE", 5, 6, [
        P(1, 99, perc(38, 25, 55, 75)), P(14, 56, fast(78)), P(1, 95, perc(42, 28, 55, 80), det=6),
        P(1, 74, perc(55, 30, 60, 60)), P(1, 85, perc(40, 25, 55, 80), det=8), P(1, 62, perc(50, 30, 60, 50))])),
    ("E.Piano Soft", 60, 0.6, 1.6, voice("EP SOFT", 5, 3, [
        P(1, 99, perc(35, 22, 55, 80)), P(14, 44, fast(80)), P(1, 92, perc(38, 25, 55, 80), det=6),
        P(1, 60, perc(50, 30, 60, 55)), P(1, 82, perc(36, 24, 55, 80), det=8), P(1, 50, perc(55, 30, 60, 45))])),
    ("DX Bell", 72, 0.4, 2.6, voice("DX BELL", 5, 0, [
        P(1, 99, perc(28, 22, 40, 70)), P(3, 76, perc(32, 25, 45, 60), fine=50),
        P(2, 82, perc(30, 24, 40, 65)), P(7, 58, perc(40, 30, 45, 50)),
        P(0, 72, perc(26, 22, 40, 70)), P(1, 52, perc(35, 28, 45, 50))])),
    ("Glass Bell", 72, 0.4, 2.4, voice("GLASSBELL", 5, 2, [
        P(1, 99, perc(30, 22, 45, 70)), P(5, 70, perc(36, 28, 45, 55)),
        P(4, 74, perc(34, 26, 45, 60)), P(1, 60, perc(40, 30, 50, 40), fine=41),
        P(2, 66, perc(32, 24, 45, 60), det=9), P(9, 46, perc(45, 32, 50, 40))])),
    ("Tubular", 67, 0.4, 2.6, voice("TUBULAR", 5, 0, [
        P(1, 99, perc(26, 20, 40, 75)), P(3, 80, perc(28, 22, 40, 60), fine=50),
        P(2, 75, perc(26, 20, 40, 70), fine=76), P(1, 55, perc(35, 26, 45, 40)),
        P(4, 62, perc(30, 22, 40, 60), fine=20), P(1, 40, perc(40, 30, 45, 30))])),
    ("Marimba", 72, 0.3, 1.0, voice("MARIMBA", 5, 0, [
        P(1, 99, perc(62, 40, 60, 40)), P(3, 70, fast(90)),
        P(4, 62, perc(75, 45, 60, 20)), P(1, 42, fast(92)), off, off])),
    ("Vibes", 72, 0.4, 2.0, voice("VIBES", 5, 0, [
        P(1, 99, perc(32, 24, 45, 75)), P(4, 62, fast(80)),
        P(1, 90, perc(34, 26, 45, 75), det=8), P(3, 44, fast(85)),
        P(4, 50, perc(50, 30, 50, 40)), off])),
    ("Kalimba", 67, 0.3, 1.4, voice("KALIMBA", 5, 2, [
        P(1, 99, perc(48, 32, 55, 55)), P(7, 66, fast(94)),
        P(3, 48, perc(60, 40, 55, 30)), P(1, 50, fast(90)), off, off])),
    ("Music Box", 79, 0.3, 1.8, voice("MUSICBOX", 5, 0, [
        P(2, 99, perc(36, 26, 50, 65)), P(6, 58, fast(85)),
        P(5, 70, perc(42, 30, 50, 50)), P(1, 40, fast(88)),
        P(8, 50, perc(50, 34, 50, 30)), off])),
    ("FM Harp", 60, 0.4, 1.6, voice("FM HARP", 5, 3, [
        P(1, 99, perc(40, 26, 50, 70)), P(2, 64, perc(65, 40, 55, 30)),
        P(1, 85, perc(42, 28, 50, 70), det=6), P(4, 48, fast(80)), off, off])),
    ("FM Bass Punch", 36, 0.5, 0.6, voice("BASSPUNCH", 1, 6, [
        P(1, 99, perc(45, 30, 60, 85)), P(1, 84, perc(70, 40, 60, 45)),
        P(0, 88, perc(40, 30, 60, 90)), P(1, 70, perc(60, 40, 60, 60)),
        P(1, 62, perc(55, 40, 60, 60)), P(1, 58, perc(60, 40, 60, 60))])),
    ("Slap Bass", 36, 0.4, 0.5, voice("SLAP BASS", 1, 5, [
        P(1, 99, perc(55, 35, 65, 70)), P(1, 86, fast(86)),
        P(0, 85, perc(50, 32, 65, 80)), P(3, 60, fast(90)),
        P(1, 55, fast(80)), P(2, 52, fast(85))])),
    ("Sub Bass", 36, 0.8, 0.5, voice("SUB BASS", 5, 0, [
        P(1, 99, sus(99, 40, 92, 60)), P(1, 46, sus(99, 60, 80, 60)),
        P(0, 92, sus(99, 40, 92, 60)), off, off, off])),
    ("Growl Bass", 36, 0.7, 0.5, voice("GROWLBASS", 1, 7, [
        P(1, 99, sus(99, 45, 85, 65)), P(1, 82, sus(80, 30, 70, 60)),
        P(0, 90, sus(99, 45, 85, 65)), P(1, 74, sus(70, 25, 75, 60)),
        P(1, 70, sus(60, 25, 70, 60)), P(1, 72, sus(70, 30, 80, 60))])),
    ("Saw Lead", 60, 1.0, 0.6, voice("SAW LEAD", 32, 6, [
        P(1, 80, sus(99, 50, 92, 60), det=9), P(2, 60, sus(99, 50, 85, 60)), off, off,
        P(1, 82, sus(99, 50, 92, 60), det=5), P(1, 99, sus(99, 50, 95, 60))])),
    ("Square Lead", 60, 1.0, 0.6, voice("SQUARELEAD", 5, 0, [
        P(1, 99, sus(99, 50, 92, 60)), P(2, 78, sus(99, 50, 90, 60)),
        P(1, 80, sus(99, 50, 90, 60), det=9), P(2, 70, sus(99, 50, 88, 60)), off, off])),
    ("Sync Lead", 60, 1.0, 0.6, voice("SYNC LEAD", 1, 4, [
        P(1, 99, sus(99, 50, 92, 60)), P(3, 85, sus(99, 40, 80, 60)),
        P(1, 80, sus(99, 50, 90, 60), det=9), P(1, 74, sus(99, 30, 70, 60)),
        P(5, 60, sus(99, 30, 70, 60)), P(1, 60, sus(99, 30, 75, 60))])),
    ("FM Brass", 60, 1.2, 0.6, voice("FM BRASS", 22, 6, [
        P(1, 99, sus(70, 50, 90, 55)), P(1, 84, sus(52, 45, 82, 55)),
        P(1, 92, sus(68, 50, 90, 55), det=6), P(1, 90, sus(68, 50, 90, 55), det=8),
        P(2, 84, sus(66, 50, 88, 55)), P(1, 80, sus(56, 45, 80, 55))])),
    ("FM Strings", 60, 1.6, 1.2, voice("FM STRING", 22, 4, [
        P(1, 99, sus(45, 40, 92, 45)), P(1, 62, sus(40, 40, 85, 45)),
        P(1, 94, sus(44, 40, 92, 45), det=5), P(2, 82, sus(42, 40, 90, 45), det=9),
        P(1, 90, sus(46, 40, 92, 45), det=9), P(1, 60, sus(40, 40, 85, 45))])),
    ("Warm Pad", 60, 2.0, 1.6, voice("WARM PAD", 5, 3, [
        P(1, 99, sus(40, 35, 94, 40)), P(1, 56, sus(35, 35, 88, 40)),
        P(1, 92, sus(38, 35, 94, 40), det=5), P(2, 50, sus(30, 35, 85, 40)),
        P(2, 84, sus(36, 35, 92, 40), det=9), P(1, 48, sus(32, 35, 85, 40))])),
    ("Glass Pad", 60, 2.0, 1.8, voice("GLASS PAD", 5, 0, [
        P(1, 99, sus(42, 35, 94, 40)), P(3, 62, sus(36, 30, 80, 40), fine=50),
        P(2, 86, sus(40, 35, 92, 40), det=9), P(5, 50, sus(34, 30, 75, 40)),
        P(1, 80, sus(38, 35, 92, 40), det=5), P(7, 40, sus(30, 30, 70, 40))])),
    ("Choir Pad", 60, 2.0, 1.4, voice("CHOIR PAD", 5, 1, [
        P(1, 99, sus(44, 35, 94, 42)), P(1, 50, sus(40, 35, 88, 42), fine=2),
        P(2, 76, sus(42, 35, 92, 42), det=8), P(3, 46, sus(40, 35, 84, 42)),
        P(3, 64, sus(44, 35, 90, 42), det=6), P(1, 42, sus(40, 35, 84, 42))])),
    ("Drawbar Organ", 60, 1.2, 0.3, voice("DRAWBAR", 32, 0, [
        P(0, 92, sus(99, 99, 99, 80)), P(1, 99, sus(99, 99, 99, 80)), P(2, 90, sus(99, 99, 99, 80)),
        P(3, 82, sus(99, 99, 99, 80)), P(4, 76, sus(99, 99, 99, 80)), P(8, 64, sus(99, 99, 99, 80))])),
    ("Perc Organ", 60, 1.2, 0.3, voice("PERCORGAN", 32, 0, [
        P(1, 99, sus(99, 99, 99, 80)), P(2, 88, sus(99, 99, 99, 80)), P(3, 92, perc(60, 40, 70, 0)),
        P(4, 70, sus(99, 99, 99, 80)), P(0, 85, sus(99, 99, 99, 80)), P(6, 60, sus(99, 99, 99, 80))])),
    ("FM Clav", 60, 0.4, 0.5, voice("FM CLAV", 5, 5, [
        P(1, 99, perc(60, 40, 70, 50)), P(3, 80, fast(84)),
        P(1, 80, perc(62, 40, 70, 40), det=9), P(5, 60, fast(88)), off, off])),
    ("Pluck Synth", 60, 0.4, 0.9, voice("PLUCKSYNTH", 1, 6, [
        P(1, 99, perc(50, 32, 60, 60)), P(1, 80, fast(76)),
        P(1, 85, perc(50, 32, 60, 60), det=9), P(2, 66, fast(80)),
        P(1, 60, fast(70)), P(1, 60, fast(75))])),
    ("FM Koto", 60, 0.4, 1.4, voice("FM KOTO", 5, 4, [
        P(1, 99, perc(44, 30, 55, 55)), P(3, 74, fast(86)),
        P(2, 70, perc(50, 32, 55, 40)), P(5, 50, fast(90)), off, off])),
    ("FM Kick", 36, 0.35, 0.6, voice("FM KICK", 5, 0, [
        P(1, 99, perc(42, 34, 60, 30), fine=74, mode=1), P(2, 75, fast(90), fine=30, mode=1),
        off, off, off, off])),
    ("FM Snare", 48, 0.3, 0.5, voice("FM SNARE", 5, 7, [
        P(2, 99, perc(55, 45, 65, 20), fine=26, mode=1), P(3, 70, fast(86), fine=40, mode=1),
        P(3, 85, perc(52, 42, 65, 20), fine=50, mode=1), P(3, 90, fast(62), fine=70, mode=1), off, off])),
    ("FM Tom", 45, 0.35, 0.7, voice("FM TOM", 5, 0, [
        P(2, 99, perc(44, 36, 60, 30), fine=8, mode=1), P(2, 66, fast(90), fine=40, mode=1),
        off, off, off, off])),
    ("Metal Hit", 60, 0.2, 1.4, voice("METAL HIT", 5, 0, [
        P(1, 99, perc(40, 30, 50, 40)), P(1, 85, perc(48, 34, 50, 30), fine=41),
        P(3, 80, perc(42, 30, 50, 40), fine=50), P(7, 74, perc(50, 36, 50, 30), fine=7),
        P(5, 70, perc(44, 30, 50, 30), fine=11), P(11, 60, perc(52, 36, 50, 20))])),
    ("Space FX", 60, 2.0, 2.0, voice("SPACE FX", 5, 2, [
        P(1, 99, sus(30, 30, 90, 30)), P(3, 70, sus(25, 30, 60, 30), fine=50),
        P(2, 80, sus(28, 30, 88, 30), fine=41), P(7, 56, sus(22, 28, 55, 30), fine=7),
        P(1, 74, sus(26, 30, 90, 30), det=9), P(5, 50, sus(20, 25, 50, 30))])),
]

def finish(y, fade_ms=20):
    m = float(np.max(np.abs(y))) or 1.0
    y = y / m * 0.89
    # cut the silent end (below -60 dB), keep a short fade
    idx = np.where(np.abs(y) > 0.001)[0]
    end = min(len(y), (idx[-1] + int(0.05 * fm6.SR)) if len(idx) else len(y))
    y = y[:end]
    f = int(fade_ms * fm6.SR / 1000)
    y[-f:] *= np.linspace(1, 0, f)
    return y


def write_wav(path, y):
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(fm6.SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype("<i2").tobytes())


NOTE = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name, note, hold, tail, v in BANK:
        y = finish(fm6.render(v, note, 100, hold, tail))
        label = name if name.startswith("FM ") else "FM " + name
        fn = f"{label} {NOTE[note % 12]}{note // 12 - 1}.wav"
        write_wav(os.path.join(OUT, fn), y)
        total += os.path.getsize(os.path.join(OUT, fn))
        print(f"{fn:34s} {len(y) / fm6.SR:4.2f} s")
    with open(os.path.join(OUT, "MakerHip_FM.syx"), "wb") as f:
        f.write(fm6.write_bank([v for _, _, _, _, v in BANK]))
    print("bank", len(BANK), "voices,", round(total / 1048576, 2), "MB of WAV")
