#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "klatt_fx.h"
#include "klatt_tables.h"

/* The original relies on >> sign-extending negative operands, which C leaves
   implementation-defined. Every compiler we target does this; fail the build
   rather than produce silently wrong audio on one that does not. */
typedef char kfx_needs_arithmetic_shift[((int32_t)-8 >> 1) == -4 ? 1 : -1];

uint32_t klatt_rand(int16_t *out, int32_t n, uint32_t seed)
{
    int32_t i = 0;

    for (; i + 3 < n; i += 4) {
        seed = seed * 0x19660du + 0x3c6ef35fu;
        int16_t r0 = (int16_t)(seed & 0xffffu);
        seed = seed * 0x19660du + 0x3c6ef35fu;
        int16_t r1 = (int16_t)(seed & 0xffffu);
        seed = seed * 0x19660du + 0x3c6ef35fu;
        int16_t r2 = (int16_t)(seed & 0xffffu);
        seed = seed * 0x19660du + 0x3c6ef35fu;
        int16_t r3 = (int16_t)(seed & 0xffffu);
        out[0] = r0;
        out[1] = r1;
        out[2] = r2;
        out[3] = r3;
        out += 4;
    }
    for (; i < n; i++) {
        seed = seed * 0x19660du + 0x3c6ef35fu;
        *out++ = (int16_t)(seed & 0xffffu);
    }
    return seed;
}

int16_t fxdivl(int32_t num, int32_t den)
{
    int      positive = 1;
    int16_t  result;
    uint32_t n, q;
    int32_t  shift;

    if (den < 0) {
        den = -den;
        num = -num;
    }
    if (num < 0) {
        positive = 0;
        num = -num;
    }

    /* Saturation is not an early exit in the original: it still runs the sign
       fixup below, so a saturated negative quotient comes back as -32767. */
    if (den == 0 || num >= den) {
        result = 0x7fff;
    } else if (num == 0) {
        result = 0;
    } else {
        /* Normalising stalls forever when num's low 16 bits are all zero,
            because num << 16 is then zero and no shift ever sets bit 31. The
            original has the same hole; the engine only feeds it small
            magnitudes. */
        n = (uint32_t)num << 16;
        shift = 16;
        while ((n & 0x80000000u) == 0) {
            n <<= 1;
            shift++;
        }

        q = n / (uint32_t)den;
        q <<= (31 - shift);

        result = (int16_t)(q >> 16);
        if (q & 0x8000u)
            result = (int16_t)(result + 1);
    }

    if (!positive && result != 0)
        result = (int16_t)(-result);

    return result;
}


void fxmul_vector(const int32_t *__restrict src, int16_t coef, int32_t *__restrict acc, int32_t n)
{
#if defined(__aarch64__) || defined(__ARM_NEON)
    if (n >= 8) {
        int32_t i = 0;
        int32x4_t v_coef = vdupq_n_s32((int32_t)coef);
        int32x4_t v_shift = vdupq_n_s32(15);

        for (; i + 3 < n; i += 4) {
            int32x4_t v_src = vld1q_s32(&src[i]);
            int32x4_t v_acc = vld1q_s32(&acc[i]);

            /* Multiply and shift right by 15 (Q15 fixed-point) */
            int64x2_t prod_lo = vmull_s32(vget_low_s32(v_src), vget_low_s32(v_coef));
            int64x2_t prod_hi = vmull_s32(vget_high_s32(v_src), vget_high_s32(v_coef));

            int32x4_t res = vcombine_s32(
                vshrn_n_s64(prod_lo, 15),
                vshrn_n_s64(prod_hi, 15)
            );

            v_acc = vaddq_s32(v_acc, res);
            vst1q_s32(&acc[i], v_acc);
        }

        for (; i < n; i++)
            acc[i] += fxmul_scaled(coef, src[i]);
        return;
    }
#endif
    int32_t i = 0;

    for (; i + 3 < n; i += 4) {
        int32_t m0 = fxmul_scaled(coef, src[i]);
        int32_t m1 = fxmul_scaled(coef, src[i + 1]);
        int32_t m2 = fxmul_scaled(coef, src[i + 2]);
        int32_t m3 = fxmul_scaled(coef, src[i + 3]);
        acc[i] += m0;
        acc[i + 1] += m1;
        acc[i + 2] += m2;
        acc[i + 3] += m3;
    }
    for (; i < n; i++)
        acc[i] += fxmul_scaled(coef, src[i]);
}

void fxmul1_vector(const int16_t *__restrict src, int16_t coef, int32_t *__restrict acc, int32_t n)
{
#if defined(__aarch64__) || defined(__ARM_NEON)
    if (n >= 8) {
        int32_t i = 0;
        int32x4_t v_coef = vdupq_n_s32((int32_t)coef);
        int32x4_t v_shift15 = vdupq_n_s32(15);
        int32x4_t v_shift4 = vdupq_n_s32(4);

        for (; i + 3 < n; i += 4) {
            /* Load 4 int16_t values and extend to int32_t */
            int16x4_t v_src16 = vld1_s16(&src[i]);
            int32x4_t v_src = vshll_n_s16(v_src16, 4);  /* << 4 */

            int32x4_t v_acc = vld1q_s32(&acc[i]);

            /* Multiply and shift right by 15 (Q15 fixed-point) */
            int64x2_t prod_lo = vmull_s32(vget_low_s32(v_src), vget_low_s32(v_coef));
            int64x2_t prod_hi = vmull_s32(vget_high_s32(v_src), vget_high_s32(v_coef));

            int32x4_t res = vcombine_s32(
                vshrn_n_s64(prod_lo, 15),
                vshrn_n_s64(prod_hi, 15)
            );

            v_acc = vaddq_s32(v_acc, res);
            vst1q_s32(&acc[i], v_acc);
        }

        for (; i < n; i++)
            acc[i] += fxmul_scaled(coef, (int32_t)src[i] << 4);
        return;
    }
#endif
    int32_t i = 0;

    for (; i + 3 < n; i += 4) {
        int32_t m0 = fxmul_scaled(coef, (int32_t)src[i] << 4);
        int32_t m1 = fxmul_scaled(coef, (int32_t)src[i + 1] << 4);
        int32_t m2 = fxmul_scaled(coef, (int32_t)src[i + 2] << 4);
        int32_t m3 = fxmul_scaled(coef, (int32_t)src[i + 3] << 4);
        acc[i] += m0;
        acc[i + 1] += m1;
        acc[i + 2] += m2;
        acc[i + 3] += m3;
    }
    for (; i < n; i++)
        acc[i] += fxmul_scaled(coef, (int32_t)src[i] << 4);
}

int32_t db2lin(int32_t db)
{
    int32_t t, quot, rem;

    if (db <= 0)
        return 0;

    /* 299/90 is log2(10) to four places, so this is dB expressed in
       twentieths of an octave, clamped at 20 doublings. */
    t = mul32(db, 299) / 90;
    if (t >= 400)
        t = 400;

    quot = t / 20;
    rem = t % 20;

    return fxmul_scaled(klatt_fxl2[rem], 2 << quot);
}

const char KlattVersionString[] =
    "\r\nKlattID version 4.0 \xa9 International Business Machines, Inc. "
    "1996, 1997 \r\n";

int verifyKlattHandle(void *handle)
{
    return strcmp(*(char **)handle, KlattVersionString) == 0;
}

typedef char filter_parms_is_84_bytes[sizeof(filter_parms) == 84 ? 1 : -1];

/* A two-pole resonator. It keeps its history in the sample buffer itself,
   two slots ahead of the pointer it was handed, rather than in locals.
   The three products are weighted 1, 2 and 4 on the way out, so the three
   coefficients are held at three different fixed-point scales. */
static void pole_filter_wide(filter_parms *fp, int32_t *buf, int32_t n);

void pole_filter(filter_parms *__restrict fp, int32_t *__restrict buf, int32_t n)
{
#if defined(__aarch64__) || defined(__ARM_NEON)
    if (n >= 8 && !klatt_wide_on() && fp->ramp == 0) {
        pole_filter_neon(fp, buf, n);
        return;
    }
#endif

    int32_t i, count, k, t1, t2, t3;

    if (fp->enabled == 0)
        return;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;
    i = 0;

    /* After the two above, not before them: whatever runs next over this
       buffer reads them, and leaving them as the last filter set them puts
       a step at the head of every block. */
    if (klatt_wide_on()) {
        pole_filter_wide(fp, buf, n);
        return;
    }

    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            t1 = fxmul_scaled(fp->c[k], buf[i - 2]);
            t2 = fxmul_scaled(fp->b[k], buf[i - 1]);
            t3 = fxmul_scaled(fp->a[k], buf[i]);
            buf[i] = t1 + t2 * 2 + t3 * 4;
            k++;
        }
        fp->ramp -= count;
    }

    if (i < n) {
        const int16_t sc = fp->sc;
        const int16_t sb = fp->sb;
        const int16_t sa = fp->sa;
        int32_t p2 = buf[i - 2];
        int32_t p1 = buf[i - 1];

        for (; i + 1 < n; i += 2) {
            int32_t in0 = buf[i];
            int32_t in1 = buf[i + 1];

            int32_t t3_0 = fxmul_scaled(sa, in0);
            int32_t t1_0 = fxmul_scaled(sc, p2);
            int32_t t2_0 = fxmul_scaled(sb, p1);
            int32_t t1_1 = fxmul_scaled(sc, p1);
            int32_t t3_1 = fxmul_scaled(sa, in1);

            int32_t out0 = t1_0 + t2_0 * 2 + t3_0 * 4;
            buf[i] = out0;

            int32_t t2_1 = fxmul_scaled(sb, out0);
            int32_t out1 = t1_1 + t2_1 * 2 + t3_1 * 4;
            buf[i + 1] = out1;

            p2 = out0;
            p1 = out1;
        }

        for (; i < n; i++) {
            int32_t in = buf[i];
            t1 = fxmul_scaled(sc, p2);
            t2 = fxmul_scaled(sb, p1);
            t3 = fxmul_scaled(sa, in);
            int32_t out = t1 + t2 * 2 + t3 * 4;
            buf[i] = out;
            p2 = p1;
            p1 = out;
        }
    }

    if (n > 1) {
        fp->d2 = buf[i - 2];
        fp->d1 = buf[i - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[i - 1];
    }
}

/* The same resonator with no input term and no ramp: it runs purely on its
   own history, which is what the parallel branch wants when the excitation is
   summed in somewhere else. */
void parallel0_filter(filter_parms *__restrict fp, int32_t *__restrict buf, int32_t n)
{
    int32_t i, t1, t2;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;

    if (n > 0) {
        const int16_t sc = fp->sc;
        const int16_t sb = fp->sb;
        int32_t p2 = buf[-2];
        int32_t p1 = buf[-1];

        for (i = 0; i + 1 < n; i += 2) {
            t1 = fxmul_scaled(sc, p2);
            t2 = fxmul_scaled(sb, p1);
            int32_t t1_1 = fxmul_scaled(sc, p1);

            int32_t out0 = t1 + t2 * 2;
            buf[i] = out0;

            int32_t t2_1 = fxmul_scaled(sb, out0);
            int32_t out1 = t1_1 + t2_1 * 2;
            buf[i + 1] = out1;

            p2 = out0;
            p1 = out1;
        }

        for (; i < n; i++) {
            t1 = fxmul_scaled(sc, p2);
            t2 = fxmul_scaled(sb, p1);
            int32_t out = t1 + t2 * 2;
            buf[i] = out;
            p2 = p1;
            p1 = out;
        }
    }

    if (n > 1) {
        fp->d2 = buf[n - 2];
        fp->d1 = buf[n - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[n - 1];
    }
}

void zero_filter(filter_parms *__restrict fp, const zero_ABCs *__restrict z, int32_t *__restrict buf, int32_t n)
{
    int32_t p1, p2, x, i, count, k;

    if (fp->enabled == 0)
        return;

    p2 = fp->d2;
    p1 = fp->d1;
    i = 0;

    /* While the ramp is live the coefficients come from the three-entry
       tables, one entry per sample, so a parameter change slides in instead
       of stepping. ramp above 3 would index off the front of them. */
    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            x = buf[i];
            buf[i] = (mul32(fp->a[k], x) >> 4)
                   + (mul32(fp->b[k], p1) >> 4)
                   + (mul32(fp->c[k], p2) >> 4);
            k++;
            p2 = p1;
            p1 = x;
        }
        fp->ramp -= count;
    }

    const int32_t za = z->a;
    const int32_t zb = z->b;
    const int32_t zc = z->c;

    for (; i + 3 < n; i += 4) {
        int32_t x0 = buf[i];
        int32_t x1 = buf[i + 1];
        int32_t x2 = buf[i + 2];
        int32_t x3 = buf[i + 3];

        buf[i]     = (mul32(za, x0) >> 4) + (mul32(zb, p1) >> 4) + (mul32(zc, p2) >> 4);
        buf[i + 1] = (mul32(za, x1) >> 4) + (mul32(zb, x0) >> 4) + (mul32(zc, p1) >> 4);
        buf[i + 2] = (mul32(za, x2) >> 4) + (mul32(zb, x1) >> 4) + (mul32(zc, x0) >> 4);
        buf[i + 3] = (mul32(za, x3) >> 4) + (mul32(zb, x2) >> 4) + (mul32(zc, x1) >> 4);

        p2 = x2;
        p1 = x3;
    }

    for (; i < n; i++) {
        x = buf[i];
        buf[i] = (mul32(za, x) >> 4)
               + (mul32(zb, p1) >> 4)
               + (mul32(zc, p2) >> 4);
        p2 = p1;
        p1 = x;
    }

    /* With n of zero the original saves the untouched d1 into d2 rather than
       the real d2, so a zero-length call is not a no-op. */
    if (n > 1) {
        fp->d2 = p2;
        fp->d1 = p1;
    } else {
        fp->d2 = fp->d1;
        fp->d1 = p1;
    }
}

/* ---- shaping the noise for a rate above the engine's own ---------------
 *
 * The frication and aspiration source is white: klatt_rand puts one value
 * per output sample in the buffer and nothing bounds it, so the noise
 * occupies whatever band the rate gives it. At 11,025 that is right by
 * accident -- Nyquist is 5,512 and the source is meant to stop there. Ask
 * the synthesiser for 22,050 and the same code spreads the same energy over
 * twice the band; at 44,100 there is as much of it above 11 kHz as between
 * 5.5 and 11, which is why sibilants synthesised outright go thin and hissy
 * and why raising the rate afterwards has been the only way up.
 *
 * So bound it where the engine's own rate bounds it. Two poles at 5,512
 * hertz, and a gain of sqrt(rate / 11025) to put back the power the band
 * limit takes out: white noise at rate R carries its variance over R/2 of
 * spectrum, so confining it to a fixed 5,512 without that gain would leave
 * the band quieter than 11,025 leaves it rather than the same.
 *
 * At 11,025 and below this does nothing at all, by the test below rather
 * than by the arithmetic coming out to unity. That is deliberate: it is what
 * keeps every recorded case byte for byte what it was.
 *
 * EVV_NOISE=flat asks for the old behaviour at any rate, which is what the
 * two are measured against. */

#define NOISE_BAND 5512

static int noise_shaping(void)
{
    static int decided, on = 1;

    if (!decided) {
        const char *say = getenv("EVV_NOISE");

        decided = 1;
        if (say != 0 && strcmp(say, "flat") == 0)
            on = 0;
    }
    return on;
}

/* Fourth-order Butterworth, as two biquads. A gentler filter is no use
   here: the whole transition band is the one octave between 5,512 and the
   new Nyquist at 22,050, so twelve decibels an octave arrives at Nyquist
   having taken almost nothing off. Twenty-four is the least that bites.

   Designed at the rate rather than stored, the way klatt_rates.c builds the
   resonator tables, because the rate is not known until the caller asks. */
void klatt_shape_noise(int16_t *buf, int32_t n, int32_t rate, double *z)
{
    static const double q[2] = { 0.54119610, 1.30656296 };
    double b[2][3], a[2][2], g;
    int32_t i;
    int s;

    if (n <= 0 || rate <= 11025 || !noise_shaping())
        return;

    for (s = 0; s < 2; s++) {
        double w0 = 6.283185307179586 * (double)NOISE_BAND / (double)rate;
        double c = cos(w0), al = sin(w0) / (2.0 * q[s]);
        double a0 = 1.0 + al;

        b[s][0] = (1.0 - c) / 2.0 / a0;
        b[s][1] = (1.0 - c) / a0;
        b[s][2] = b[s][0];
        a[s][0] = -2.0 * c / a0;
        a[s][1] = (1.0 - al) / a0;
    }

    /* White noise carries its variance over the whole band it is given, so
       confining it to a fixed 5,512 leaves less power in that band than
       11,025 leaves there. This puts it back. */
    g = sqrt((double)rate / 11025.0);

    for (i = 0; i < n; i++) {
        double v = buf[i];

        for (s = 0; s < 2; s++) {
            double *w = z + s * 2;
            double y = b[s][0] * v + w[0];

            w[0] = b[s][1] * v - a[s][0] * y + w[1];
            w[1] = b[s][2] * v - a[s][1] * y;
            v = y;
        }
        v *= g;
        if (v > 32767.0)
            v = 32767.0;
        else if (v < -32768.0)
            v = -32768.0;
        buf[i] = (int16_t)v;
    }
}

/* ---- the resonators, with a fraction to their name ---------------------
 *
 * pole_filter keeps its state in the output buffer as whole numbers, and
 * shifts each of the three products down by fifteen before summing them. So
 * every sample of the recursion is truncated to an integer and that error is
 * fed straight back. What a resonator does with a feedback error depends on
 * how close its poles sit to the unit circle, and raising the sample rate is
 * precisely what pushes them there: a pole at a 60 hertz bandwidth has a
 * radius of 0.983 at 11,025 and 0.996 at 44,100, and the round-off noise a
 * direct form throws off grows with the square of the distance closing.
 *
 * That is why the fixed point engine upsamples worse than a floating point
 * one while sounding the same at 11,025 -- the design is tuned where the
 * arithmetic is comfortable, and nothing about the tuning says so.
 *
 * So above the engine's own rate, keep the state in a double beside the
 * block rather than truncated in the buffer. The coefficients stay exactly
 * the ones the fixed point path built, so this separates the two questions:
 * what the state's precision costs, which is this, from what the
 * coefficients' precision costs, which is a further change if this is not
 * enough.
 *
 * The state cannot go in filter_parms: that block is IBM's field for field
 * and the thirty-two bit build checks every offset. It is keyed by the
 * resonator's own address instead, which is stable and unique because each
 * one lives in the array inside the synthesiser's block.
 *
 * UNFINISHED, and off unless EVV_WIDE=on asks for it. Where it has got to,
 * on 6 September 2026:
 *
 * EVV_WIDE=emulate runs this same shadow carrying the narrow path's own
 * arithmetic, and requires the two to come out bit for bit identical. That
 * is the check that says whether a difference is precision or structure, and
 * it has already earned itself. It said structure, twice.
 *
 * The first was the whole of the spikes: KlattSynth zeroes d1 and d2 itself
 * when a filter resets between frames, and a shadow that did not follow went
 * on ringing from state the engine had silenced. Peak 32316 against the
 * narrow path's 5027, clipping at ordinary volume, and the clipping was what
 * filled the band above 5,512 with 54 dB of nonsense. Resyncing whenever the
 * two disagree brings it to peak 4884 against 5027 and RMS 875 against 989 --
 * the same signal, near enough to argue about.
 *
 * The second is still open. Emulate and narrow first differ at sample 16 and
 * then almost everywhere, by up to 1483 counts. Sixteen is early enough to be
 * a block boundary rather than anything in the recursion, so the state handed
 * from one call to the next is the place to look, and the `n > 1' arm at the
 * end of pole_filter -- which does something odd when n is nought or one --
 * is the first suspect. As it stands the wide
 * path is wrong rather than merely different: at a quarter volume, where
 * nothing clips, it comes out 10.8 dB quieter in RMS than the narrow one and
 * with a peak three times higher, which is spikes rather than speech, and at
 * full volume those spikes clip and fill the band above 5,512 with the
 * distortion. Precision alone cannot do that -- fxmul_scaled is a block
 * floating point multiply rather than a plain shift, so the narrow path is
 * far less lossy than it looks -- so there is a structural mistake in here
 * and not just a difference of arithmetic.
 *
 * The check that would find it: make this path truncate each of the three
 * products separately, exactly as the narrow one does, and require the two
 * to agree bit for bit. If they do not, the recursion is wrong; if they do,
 * the difference really is precision and the spikes are something the
 * truncation was holding down. */

static int32_t wide_round(double y);
#define WIDES 64

static struct {
    const filter_parms *fp;
    double              d1, d2;
} wide[WIDES];

static int wide_on;

void klatt_wide_enable(int32_t rate)
{
    static int decided, allowed;

    if (!decided) {
        const char *say = getenv("EVV_WIDE");

        decided = 1;
        allowed = 0;
        if (say != 0 && strcmp(say, "on") == 0)
            allowed = 1;
        /* The same structure carrying the narrow path's own arithmetic. If
           this does not come out bit for bit identical to it, the recursion
           here is wrong and precision was never the question. */
        else if (say != 0 && strcmp(say, "emulate") == 0)
            allowed = 2;
    }
    wide_on = allowed && rate > 11025;
}

/* The wide state shadows d1 and d2 and has to follow them. KlattSynth zeroes
   the pair itself when a filter resets between frames, and a shadow that
   missed that went on ringing from state the engine had silenced -- which is
   where the spikes came from, and it was structure rather than precision:
   the same shadow carrying the narrow path's own arithmetic reproduced them
   exactly.

   So resync whenever the two disagree. In the ordinary case this path wrote
   d1 and d2 itself at the end of the last call and they still match, so the
   test costs a compare and says nothing; when anything else has written
   them, it is the only warning there is. */
static double *wide_state(filter_parms *fp)
{
    int i, spare = -1;

    for (i = 0; i < WIDES; i++) {
        if (wide[i].fp == fp) {
            if (wide_round(wide[i].d1) != fp->d1
                || wide_round(wide[i].d2) != fp->d2) {
                wide[i].d1 = (double)fp->d1;
                wide[i].d2 = (double)fp->d2;
            }
            return &wide[i].d1;
        }
        if (wide[i].fp == 0 && spare < 0)
            spare = i;
    }
    if (spare < 0)
        spare = 0;
    wide[spare].fp = fp;
    wide[spare].d1 = (double)fp->d1;
    wide[spare].d2 = (double)fp->d2;
    return &wide[spare].d1;
}

static int32_t wide_round(double y)
{
    return (int32_t)(y >= 0.0 ? y + 0.5 : y - 0.5);
}

/* The same recursion pole_filter runs, in doubles. The three weights carry
   the doubling and quadrupling that one applies on the way out. */
static void pole_filter_wide(filter_parms *fp, int32_t *buf, int32_t n)
{
    double *z = wide_state(fp);
    double y1 = z[0], y2 = z[1];
    int32_t i = 0, count, k;

    if (klatt_wide_on() == 2) {
        int32_t v1 = (int32_t)z[0], v2 = (int32_t)z[1], t1, t2, t3;

        if (fp->ramp != 0) {
            count = fp->ramp < n ? fp->ramp : n;
            k = 3 - fp->ramp;

            for (; i < count; i++) {
                t1 = fxmul_scaled(fp->c[k], v2);
                t2 = fxmul_scaled(fp->b[k], v1);
                t3 = fxmul_scaled(fp->a[k], buf[i]);
                v2 = v1;
                v1 = t1 + t2 * 2 + t3 * 4;
                buf[i] = v1;
                k++;
            }
            fp->ramp -= count;
        }
        for (; i < n; i++) {
            t1 = fxmul_scaled(fp->sc, v2);
            t2 = fxmul_scaled(fp->sb, v1);
            t3 = fxmul_scaled(fp->sa, buf[i]);
            v2 = v1;
            v1 = t1 + t2 * 2 + t3 * 4;
            buf[i] = v1;
        }
        z[0] = v1;
        z[1] = v2;
        fp->d1 = v1;
        fp->d2 = v2;
        return;
    }

    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            double y = fp->a[k] * (4.0 / 32768.0) * buf[i]
                     + fp->b[k] * (2.0 / 32768.0) * y1
                     + fp->c[k] * (1.0 / 32768.0) * y2;

            y2 = y1;
            y1 = y;
            buf[i] = wide_round(y);
            k++;
        }
        fp->ramp -= count;
    }

    if (i < n) {
        const double c_a = fp->sa * (4.0 / 32768.0);
        const double c_b = fp->sb * (2.0 / 32768.0);
        const double c_c = fp->sc * (1.0 / 32768.0);

        for (; i < n; i++) {
            double y = c_a * buf[i] + c_b * y1 + c_c * y2;
            y2 = y1;
            y1 = y;
            buf[i] = wide_round(y);
        }
    }

    z[0] = y1;
    z[1] = y2;
    fp->d1 = wide_round(y1);
    fp->d2 = wide_round(y2);
}

int klatt_wide_on(void)
{
    return wide_on;
}

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>

/* NEON-optimized pole_filter for ARM64.
 * Processes 4 samples per iteration using 128-bit NEON registers.
 * Uses 32-bit integer arithmetic with Q15 fixed-point coefficients. */
void pole_filter_neon(filter_parms *fp, int32_t *buf, int32_t n)
{
    int32_t i, count, k;
    int32_t *history = buf - 2;

    if (fp->enabled == 0)
        return;

    /* Initialize history from filter state */
    history[0] = fp->d2;
    history[1] = fp->d1;
    i = 0;

    if (klatt_wide_on()) {
        /* Delegate to existing wide implementation */
        pole_filter_wide(fp, buf, n);
        return;
    }

    /* Handle ramp phase if needed */
    if (fp->ramp != 0) {
        count = fp->ramp < n ? fp->ramp : n;
        k = 3 - fp->ramp;

        for (; i < count; i++) {
            int32_t t1 = fxmul_scaled(fp->c[k], buf[i - 2]);
            int32_t t2 = fxmul_scaled(fp->b[k], buf[i - 1]);
            int32_t t3 = fxmul_scaled(fp->a[k], buf[i]);
            buf[i] = t1 + t2 * 2 + t3 * 4;
            k++;
        }
        fp->ramp -= count;
    }

    if (i >= n) {
        if (n > 1) {
            fp->d2 = buf[i - 2];
            fp->d1 = buf[i - 1];
        } else {
            fp->d2 = fp->d1;
            fp->d1 = buf[i - 1];
        }
        return;
    }

    /* Steady-state phase: process 4 samples at a time with NEON */
    const int16_t sc = fp->sc;
    const int16_t sb = fp->sb;
    const int16_t sa = fp->sa;

    /* Load coefficients into NEON registers */
    int32x4_t v_sc = vdupq_n_s32((int32_t)sc);
    int32x4_t v_sb = vdupq_n_s32((int32_t)sb);
    int32x4_t v_sa = vdupq_n_s32((int32_t)sa);
    int32x4_t v_two = vdupq_n_s32(2);
    int32x4_t v_four = vdupq_n_s32(4);
    int32x4_t v_shift = vdupq_n_s32(15);

    int32_t p2 = buf[i - 2];
    int32_t p1 = buf[i - 1];

    /* Process 4 samples per iteration */
    for (; i + 3 < n; i += 4) {
        /* Load 4 input samples */
        int32x4_t in = vld1q_s32(&buf[i]);

        /* t1 = sc * p2 (previous-previous output) */
        int32x4_t t1 = vdupq_n_s32(fxmul_scaled(sc, p2));

        /* t2 = sb * p1 (previous output) */
        int32x4_t t2 = vdupq_n_s32(fxmul_scaled(sb, p1));

        /* t3 = sa * in (current input) - vectorized */
        int32x4_t t3;
        {
            int32_t t3_0 = fxmul_scaled(sa, buf[i]);
            int32_t t3_1 = fxmul_scaled(sa, buf[i + 1]);
            int32_t t3_2 = fxmul_scaled(sa, buf[i + 2]);
            int32_t t3_3 = fxmul_scaled(sa, buf[i + 3]);
            t3 = vld1q_s32((int32_t[4]){t3_0, t3_1, t3_2, t3_3});
        }

        /* out = t1 + t2*2 + t3*4 */
        int32x4_t out = vaddq_s32(vaddq_s32(t1, vmulq_s32(t2, v_two)), vmulq_s32(t3, v_four));

        /* Store results */
        vst1q_s32(&buf[i], out);

        /* Update history for next iteration */
        p2 = buf[i + 2];
        p1 = buf[i + 3];
    }

    /* Handle remainder samples */
    for (; i < n; i++) {
        int32_t in = buf[i];
        int32_t t1 = fxmul_scaled(sc, p2);
        int32_t t2 = fxmul_scaled(sb, p1);
        int32_t t3 = fxmul_scaled(sa, in);
        int32_t out = t1 + t2 * 2 + t3 * 4;
        buf[i] = out;
        p2 = p1;
        p1 = out;
    }

    /* Save final state */
    if (n > 1) {
        fp->d2 = buf[n - 2];
        fp->d1 = buf[n - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[n - 1];
    }
}
#endif
