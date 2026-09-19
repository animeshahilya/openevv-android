#ifndef KLATT_FX_H
#define KLATT_FX_H

#include <stdint.h>

/* IBM built clsyn.cpp for a 32-bit target where long is 32 bits. Nothing here
   may use long: on LP64 hosts that silently changes every result. */

/* imull keeps the low 32 bits and lets the rest go. Signed overflow is
   undefined in C, so wrap in unsigned and reinterpret to get the same bits. */
#if defined(__GNUC__) || defined(__clang__)
#define EVV_LIKELY(x)   __builtin_expect(!!(x), 1)
#define EVV_UNLIKELY(x) __builtin_expect(!!(x), 0)
#define EVV_INLINE static inline __attribute__((always_inline, flatten))
#define EVV_HOT __attribute__((hot))
#define EVV_COLD __attribute__((cold))
#else
#define EVV_LIKELY(x)   (x)
#define EVV_UNLIKELY(x) (x)
#define EVV_INLINE static inline
#define EVV_HOT
#define EVV_COLD
#endif

EVV_INLINE int32_t mul32(int32_t a, int32_t b)
{
    return (int32_t)((uint32_t)a * (uint32_t)b);
}

/* Both vector multiplies want (coef * x) >> 15 but must not overflow 32 bits,
   so they pre-shift x by however much its magnitude demands and take the rest
   out of the final shift. The staged form drops low bits that a wider multiply
   would keep, so it cannot be folded back into a single expression.

   How far to pre-shift is a question about x alone -- which of five ranges its
   magnitude falls in -- and the answer is always

   mul32(coef, x >> pre) >> (15 - pre)

   with pre one of 0, 4, 8, 12 and 15. Written as five comparisons it was a
   chain of unpredictable branches on every multiply, and every sample of every
   resonator goes through one: pole_filter alone was a seventh of the whole
   engine's instructions. Written as the position of the topmost bit that is
   not a copy of the sign it is a count, a load and two shifts.

   The two are the same answer, not nearly the same, and that was checked
   rather than argued: sixteen coefficients -- both signs, every magnitude,
   the extremes and the boundaries -- against all 4,294,967,296 values of x,
   identical throughout. Which matters more here than the speed, because the
   whole engine is held to IBM's samples byte for byte. */
static const unsigned char fx_pre[8] = { 15, 12, 8, 4, 0, 0, 0, 0 };

EVV_INLINE int32_t fxmul_scaled(int32_t coef, int32_t x)
{
#if defined(__GNUC__) || defined(__clang__)
    /* The first of the five ranges is much the commonest, so it keeps a test
       of its own: one compare that predicts, against a count that is always
       paid. What is left goes the branchless way. */
    if (__builtin_expect((uint32_t)(x + 0xffff) <= 0x1fffeu, 1))
        return mul32(coef, x) >> 15;
    {
        /* |x|, without a branch and without overflowing on the most negative
           value. Nought cannot reach here. */
        uint32_t s = (uint32_t)(x >> 31);
        uint32_t lz = (uint32_t)__builtin_clz(((uint32_t)x ^ s) - s);
        int      pre = (int)((0x0000000004080c0fULL >> ((lz >> 2) << 3)) & 0xff);

        return mul32(coef, x >> pre) >> (15 - pre);
    }
#else
    int32_t c = coef;

    if (x > 0) {
        if (x < 0x10000)
            return mul32(c, x) >> 15;
        if (x < 0x100000)
            return mul32(c, x >> 4) >> 11;
        if (x < 0x1000000)
            return mul32(c, x >> 8) >> 7;
        if (x < 0x10000000)
            return mul32(c, x >> 12) >> 3;
        return mul32(c, x >> 15);
    }

    if ((uint32_t)x > 0xffff0000u)
        return mul32(c, x) >> 15;
    if ((uint32_t)x > 0xfff00000u)
        return mul32(c, x >> 4) >> 11;
    if ((uint32_t)x > 0xff000000u)
        return mul32(c, x >> 8) >> 7;
    if ((uint32_t)x > 0xf0000000u)
        return mul32(c, x >> 12) >> 3;
    return mul32(c, x >> 15);
#endif
}

#include <string.h>

/* clr_vector: NEON-optimized for ARM64, falls back to memset on other platforms */
#if defined(__GNUC__) || defined(__clang__)
#define EVV_INLINE static inline __attribute__((always_inline))
#else
#define EVV_INLINE static inline
#endif

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
EVV_INLINE void clr_vector(int32_t *v, int32_t n)
{
    if (n >= 8) {
        int32x4_t zero = vdupq_n_s32(0);
        int32_t i = 0;
        for (; i + 7 < n; i += 8) {
            vst1q_s32(&v[i], zero);
            vst1q_s32(&v[i + 4], zero);
        }
        for (; i < n; i++)
            v[i] = 0;
        return;
    }
    memset(v, 0, (size_t)n * sizeof(int32_t));
}
#else
EVV_INLINE void clr_vector(int32_t *v, int32_t n)
{
    memset(v, 0, (size_t)n * sizeof(int32_t));
}
#endif
uint32_t klatt_rand(int16_t *out, int32_t n, uint32_t seed) EVV_HOT;
void     klatt_shape_noise(int16_t *buf, int32_t n, int32_t rate, double *z) EVV_HOT;
void     klatt_wide_enable(int32_t rate);
int      klatt_wide_on(void) EVV_HOT;
int16_t  fxdivl(int32_t num, int32_t den) EVV_HOT;
void fxmul_vector(const int32_t *__restrict src, int16_t coef, int32_t *__restrict acc, int32_t n) EVV_HOT;
void fxmul1_vector(const int16_t *__restrict src, int16_t coef, int32_t *__restrict acc, int32_t n) EVV_HOT;
int32_t  db2lin(int32_t db) EVV_HOT;
int      verifyKlattHandle(void *handle) EVV_COLD;

/* One resonator's working state. The synthesizer state block holds 21 of
   these in an array starting at offset 0x64. Fields still called unknown are
   ones only pole_filter and KlattSynth have touched so far. */
typedef struct {
    int16_t sa;              /* 0x00, steady state, weight on the input */
    int16_t sb;              /* 0x02, weight on y[n-1] */
    int16_t sc;              /* 0x04, weight on y[n-2] */
    /* How far sa and sb have already been shifted. pole_filter's fixed
       weights of four and two correspond to two and one, and the normal path
       always builds the coefficients at exactly those scales. Only the tilt
       table supplies coefficients at other scales, and KlattSynth normalises
       them up to these before use. */
    int8_t  sa_scale;        /* 0x06 */
    int8_t  sb_scale;        /* 0x07 */
    int32_t unknown_08;      /* 0x08 */
    int16_t a[3];            /* 0x0c, coefficients ramped over three samples */
    int16_t b[3];            /* 0x12 */
    int16_t c[3];            /* 0x18 */
    int16_t unknown_1e[3];   /* 0x1e */
    int32_t d1;              /* 0x24, previous sample */
    int32_t d2;              /* 0x28, the one before that */
    int32_t prev_freq;       /* 0x2c, last frame's frequency in hertz */
    int32_t prev_bw;         /* 0x30, last frame's bandwidth */
    int32_t unknown_34;      /* 0x34, KlattOpen sets -1 */
    int32_t unknown_38;      /* 0x38, KlattOpen sets -1 */
    /* A snapshot of the first four fields taken at the end of each frame, so
       the next frame can slide from the old coefficients to the new ones. */
    int16_t old_sa;          /* 0x3c */
    int16_t old_sb;          /* 0x3e */
    int16_t old_sc;          /* 0x40 */
    int8_t  old_sa_scale;    /* 0x42 */
    int8_t  old_sb_scale;    /* 0x43 */
    int32_t old_unknown_08;  /* 0x44 */
    int32_t enabled;         /* 0x48, frames this resonator stays live */
    int32_t ramp;            /* 0x4c, samples left of the coefficient ramp */
    int32_t frames;          /* 0x50, frames since this resonator woke up */
} filter_parms;

/* Steady-state coefficients a zero uses once its ramp has run out. */
typedef struct {
    int16_t a;
    int16_t b;
    int16_t c;
} zero_ABCs;

void zero_filter(filter_parms *__restrict fp, const zero_ABCs *__restrict z, int32_t *__restrict buf, int32_t n) EVV_HOT;

/* buf must have two writable samples before it: the resonator seeds its own
   history there and reads them back as y[n-1] and y[n-2]. */
void pole_filter(filter_parms *__restrict fp, int32_t *__restrict buf, int32_t n) EVV_HOT;
void parallel0_filter(filter_parms *__restrict fp, int32_t *__restrict buf, int32_t n) EVV_HOT;

#if defined(__aarch64__) || defined(__ARM_NEON)
void pole_filter_neon(filter_parms *__restrict fp, int32_t *__restrict buf, int32_t n) EVV_HOT;
#endif

extern const char KlattVersionString[];

#endif
