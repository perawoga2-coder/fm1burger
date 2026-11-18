#!/usr/bin/env python3
"""
A 6-operator FM voice renderer for DX7-format patches (the format the M-VAVE FM-1 uses).

Its structure (32 algorithms, envelopes in log level units, level / rate / velocity scaling,
coarse / fine / detune frequencies, operator feedback) follows the open DX7 engine in Google's
"Music Synthesizer for Android" (Apache License 2.0, see LICENSES/msfa-NOTICE.txt). This is a
floating point re-implementation, not a copy. The app's FmRender.kt does the same in Kotlin.

Patch format: the unpacked 155-byte DX7 voice (VCED) as a list of ints, operator 6 first.
"""
import math

import numpy as np

SR = 44100
N = 64                       # block size (envelopes are worked out once a block, like the original)

# per operator, OP6 first: out bus (1, 2) or 4 = add to the output; in bus (1, 2) << 4; 0x40 uses feedback; 0x80 writes it
ALGORITHMS = [
    [0xc1, 0x11, 0x11, 0x14, 0x01, 0x14], [0x01, 0x11, 0x11, 0x14, 0xc1, 0x14], [0xc1, 0x11, 0x14, 0x01, 0x11, 0x14],
    [0x41, 0x11, 0x94, 0x01, 0x11, 0x14], [0xc1, 0x14, 0x01, 0x14, 0x01, 0x14], [0x41, 0x94, 0x01, 0x14, 0x01, 0x14],
    [0xc1, 0x11, 0x05, 0x14, 0x01, 0x14], [0x01, 0x11, 0xc5, 0x14, 0x01, 0x14], [0x01, 0x11, 0x05, 0x14, 0xc1, 0x14],
    [0x01, 0x05, 0x14, 0xc1, 0x11, 0x14], [0xc1, 0x05, 0x14, 0x01, 0x11, 0x14], [0x01, 0x05, 0x05, 0x14, 0xc1, 0x14],
    [0xc1, 0x05, 0x05, 0x14, 0x01, 0x14], [0xc1, 0x05, 0x11, 0x14, 0x01, 0x14], [0x01, 0x05, 0x11, 0x14, 0xc1, 0x14],
    [0xc1, 0x11, 0x02, 0x25, 0x05, 0x14], [0x01, 0x11, 0x02, 0x25, 0xc5, 0x14], [0x01, 0x11, 0x11, 0xc5, 0x05, 0x14],
    [0xc1, 0x14, 0x14, 0x01, 0x11, 0x14], [0x01, 0x05, 0x14, 0xc1, 0x14, 0x14], [0x01, 0x14, 0x14, 0xc1, 0x14, 0x14],
    [0xc1, 0x14, 0x14, 0x14, 0x01, 0x14], [0xc1, 0x14, 0x14, 0x01, 0x14, 0x04], [0xc1, 0x14, 0x14, 0x14, 0x04, 0x04],
    [0xc1, 0x14, 0x14, 0x04, 0x04, 0x04], [0xc1, 0x05, 0x14, 0x01, 0x14, 0x04], [0x01, 0x05, 0x14, 0xc1, 0x14, 0x04],
    [0x04, 0xc1, 0x11, 0x14, 0x01, 0x14], [0xc1, 0x14, 0x01, 0x14, 0x04, 0x04], [0x04, 0xc1, 0x11, 0x14, 0x04, 0x04],
    [0xc1, 0x14, 0x04, 0x04, 0x04, 0x04], [0xc4, 0x04, 0x04, 0x04, 0x04, 0x04],
]

LEVEL_LUT = [0, 5, 9, 13, 17, 20, 23, 25, 27, 29, 31, 33, 35, 37, 39, 41, 42, 43, 45, 46]
VEL_DATA = [0, 70, 86, 97, 106, 114, 121, 126, 132, 138, 142, 148, 152, 156, 160, 163, 166, 170, 173, 174, 178,
            181, 184, 186, 189, 190, 194, 196, 198, 200, 202, 205, 206, 209, 211, 214, 216, 218, 220, 222, 224, 225,
            227, 229, 230, 232, 233, 235, 237, 238, 240, 241, 242, 243, 244, 246, 246, 248, 249, 250, 251, 252, 253,
            254]
EXP_SCALE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 14, 16, 19, 23, 27, 33, 39, 47, 56, 66, 80, 94, 110, 126, 142, 158,
             174, 190, 206, 222, 238, 250]


def scale_out(l):
    return l + 28 if l >= 20 else LEVEL_LUT[l]


def scale_vel(vel, sens):
    v = VEL_DATA[max(0, min(127, vel)) >> 1] - 239
    return ((sens * v + 7) >> 3) << 4


def scale_rate(note, sens):
    x = min(31, max(0, note // 3 - 7))
    return (sens * x) >> 3


def scale_curve(group, depth, curve):
    if curve in (0, 3):
        s = (group * depth * 329) >> 12
    else:
        s = (EXP_SCALE[min(group, len(EXP_SCALE) - 1)] * depth * 329) >> 15
    return -s if curve < 2 else s


def scale_level(note, bp, ld, rd, lc, rc):
    off = note - bp - 17
    return scale_curve(off // 3, rd, rc) if off >= 0 else scale_curve((-off) // 3, ld, lc)


class Env:
    """The 4 rate / 4 level envelope, in log level units (256 per octave of amplitude)."""

    def __init__(self, rates, levels, outlevel, rate_scaling):
        self.r, self.l, self.ol, self.rs = rates, levels, outlevel, rate_scaling
        self.level = 0.0
        self.down = True
        self.advance(0)

    def advance(self, ix):
        self.ix = ix
        if ix < 4:
            a = (scale_out(self.l[ix]) >> 1 << 6) + self.ol - 4256
            self.target = max(16, a) * 65536.0
            self.rising = self.target > self.level
            q = min(63, ((self.r[ix] * 41) >> 6) + self.rs)
            self.inc = float((4 + (q & 3)) << (2 + 6 + (q >> 2)))

    def keyup(self):
        if self.down:
            self.down = False
            self.advance(3)

    def step(self):
        """One block. Returns the level (Q24 log2)."""
        if self.ix < 3 or (self.ix < 4 and not self.down):
            if self.rising:
                jump = 1716 * 65536.0
                if self.level < jump:
                    self.level = jump
                self.level += ((17 * 16777216.0 - self.level) / 16777216.0) * self.inc
                if self.level >= self.target:
                    self.level = self.target
                    self.advance(self.ix + 1)
            else:
                self.level -= self.inc
                if self.level <= self.target:
                    self.level = self.target
                    self.advance(self.ix + 1)
        return self.level


COARSE = [0.5] + list(range(1, 32))


def op_freq(note, mode, coarse, fine, detune):
    if mode == 0:
        f = 440.0 * 2 ** ((note - 69) / 12) * COARSE[coarse & 31] * (1 + 0.01 * fine)
        return f * 2 ** (12606 * (detune - 7) / 16777216.0)
    f = 10 ** ((coarse & 3) + fine / 100.0)
    return f * (2 ** (13457 * (detune - 7) / 16777216.0) if detune > 7 else 1.0)


def render(v, note=60, vel=100, hold=1.0, tail=1.5, sr=SR):
    """Renders one note of a 155-value VCED voice. Returns a float numpy array (not normalised)."""
    envs, freqs = [], []
    for op in range(6):
        o = op * 21
        ol = scale_out(v[o + 16])
        ol = min(127, ol + scale_level(note, v[o + 8], v[o + 9], v[o + 10], v[o + 11], v[o + 12]))
        ol = max(0, (ol << 5) + scale_vel(vel, v[o + 15]))
        envs.append(Env(v[o:o + 4], v[o + 4:o + 8], ol, scale_rate(note, v[o + 13])))
        freqs.append(op_freq(note, v[o + 17], v[o + 18], v[o + 19], v[o + 20]) / sr)
    alg = ALGORITHMS[v[134] & 31]
    fb = v[135] & 7
    fb_shift = 8 - fb if fb else 16
    total = int((hold + tail) * sr) // N * N
    out = np.zeros(total)
    phase = [0.0] * 6
    gain_prev = [0.0] * 6
    fb_y0 = fb_y1 = 0.0
    hold_blocks = int(hold * sr) // N
    t = np.arange(N)
    for b in range(total // N):
        if b == hold_blocks:
            for e in envs:
                e.keyup()
        bus = [np.zeros(N), np.zeros(N), np.zeros(N)]
        has = [True, False, False]
        for op in range(6):
            flags = alg[op]
            lvl = envs[op].step()
            g1 = gain_prev[op]
            g2 = 2.0 ** (lvl / 16777216.0 - 14.0)
            gain_prev[op] = g2
            add = (flags & 4) != 0
            inbus = (flags >> 4) & 3
            outbus = 0 if add else (flags & 3)
            if g1 < 2 ** -9 and g2 < 2 ** -9:
                if not add:
                    has[outbus] = False
                phase[op] += freqs[op] * N
                continue
            gain = g1 + (g2 - g1) * (t + 1) / N
            ph = phase[op] + freqs[op] * t
            if (flags & 0xc0) == 0xc0 and fb_shift < 16 and (inbus == 0 or not has[inbus]):
                y = np.empty(N)
                for i in range(N):
                    sfb = (fb_y0 + fb_y1) / 2.0 / (2 ** fb_shift)
                    fb_y0 = fb_y1
                    fb_y1 = math.sin(2 * math.pi * (ph[i] + sfb)) * gain[i]
                    y[i] = fb_y1
            elif inbus == 0 or not has[inbus]:
                y = np.sin(2 * np.pi * ph) * gain
            else:
                y = np.sin(2 * np.pi * (ph + bus[inbus])) * gain
            if add and has[outbus]:
                bus[outbus] = bus[outbus] + y
            else:
                bus[outbus] = y
            has[outbus] = True
            phase[op] = (phase[op] + freqs[op] * N) % 1.0
        out[b * N:(b + 1) * N] = bus[0]
    return out


# ---------------- DX7 sysex: 32 voices in one bulk dump ----------------

def unpack_voice(bulk):
    """128 packed bytes -> 155 VCED values (the same layout as the DX7 single voice dump)."""
    p = [0] * 155
    for op in range(6):
        b = bulk[op * 17: op * 17 + 17]
        o = op * 21
        p[o:o + 11] = b[0:11]
        p[o + 11] = b[11] & 3
        p[o + 12] = (b[11] >> 2) & 3
        p[o + 13] = b[12] & 7
        p[o + 20] = b[12] >> 3
        p[o + 14] = b[13] & 3
        p[o + 15] = b[13] >> 2
        p[o + 16] = b[14]
        p[o + 17] = b[15] & 1
        p[o + 18] = b[15] >> 1
        p[o + 19] = b[16]
    p[126:135] = bulk[102:111]
    p[135] = bulk[111] & 7
    p[136] = bulk[111] >> 3
    p[137:141] = bulk[112:116]
    p[141] = bulk[116] & 1
    p[142] = (bulk[116] >> 1) & 7
    p[143] = bulk[116] >> 4
    p[144:155] = bulk[117:128]
    return p


def pack_voice(p):
    b = [0] * 128
    for op in range(6):
        o = op * 21
        bo = op * 17
        b[bo:bo + 11] = p[o:o + 11]
        b[bo + 11] = (p[o + 11] & 3) | ((p[o + 12] & 3) << 2)
        b[bo + 12] = (p[o + 13] & 7) | ((p[o + 20] & 15) << 3)
        b[bo + 13] = (p[o + 14] & 3) | ((p[o + 15] & 7) << 2)
        b[bo + 14] = p[o + 16]
        b[bo + 15] = (p[o + 17] & 1) | ((p[o + 18] & 31) << 1)
        b[bo + 16] = p[o + 19]
    b[102:111] = p[126:135]
    b[111] = (p[135] & 7) | ((p[136] & 1) << 3)
    b[112:116] = p[137:141]
    b[116] = (p[141] & 1) | ((p[142] & 7) << 1) | ((p[143] & 7) << 4)
    b[117:128] = p[144:155]
    return [x & 0x7f for x in b]


def read_bank(data):
    """A 32 voice bulk dump (.syx, 4104 bytes) -> list of 32 (name, voice)."""
    i = data.find(bytes([0xF0, 0x43]))
    if i < 0 or len(data) < i + 6 + 4096:
        raise ValueError("not a DX7 32 voice bank")
    body = data[i + 6: i + 6 + 4096]
    out = []
    for k in range(32):
        v = unpack_voice(list(body[k * 128:(k + 1) * 128]))
        name = bytes(v[145:155]).decode("ascii", "replace").strip()
        out.append((name, v))
    return out


def write_bank(voices):
    body = []
    for v in voices[:32]:
        body += pack_voice(v)
    while len(body) < 4096:
        body += pack_voice(voices[0])
    chk = (-sum(body)) & 0x7f
    return bytes([0xF0, 0x43, 0x00, 0x09, 0x20, 0x00] + body + [chk, 0xF7])
