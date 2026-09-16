#include <stdint.h>
#include <arm_neon.h>

#include "klatt_fx.h"
#include "klatt_fx.h"

/* ARM NEON optimized pole_filter - processes 2 samples per iteration using 64-bit NEON registers */
static void pole_filter_neon(filter_parms *fp, int32_t *buf, int32_t n)
{
    if (fp->enabled == 0)
        return;

    buf[-2] = fp->d2;
    buf[-1] = fp->d1;

    int32_t idx = 0;

    if (fp->ramp != 0) {
        int32_t count = fp->ramp < n ? fp->ramp : n;
        int32_t k = 3 - fp->ramp;

        for (int32_t j = 0; j < count; j++) {
            int32_t t1 = fxmul_scaled(fp->c[k], buf[j - 2]);
            int32_t t2 = fxmul_scaled(fp->b[k], buf[j - 1]);
            int32_t t3 = fxmul_scaled(fp->a[k], buf[j]);
            buf[j] = t1 + t2 * 2 + t3 * 4;
            k++;
        }
        fp->ramp -= count;
    }

    if (fp->enabled == 0)
        return;

    int32_t i = 0;
    int32_t p2 = buf[-2];
    int32_t p1 = buf[-1];

    int32_t sc = fp->sc;
    int32_t sb = fp->sb;
    int32_t sa = fp->sa;

    /* Process 2 samples at a time using 64-bit NEON registers */
    for (; idx + 1 < n; idx += 2) {
        int32_t in0 = buf[idx];
        int32_t in1 = buf[idx + 1];

        int32_t t3_0 = fxmul_scaled(sa, buf[idx]);
        int32_t t1_0 = fxmul_scaled(sc, p2);
        int32_t t2_0 = fxmul_scaled(sb, p1);
        int32_t t1_1 = fxmul_scaled(sc, p1);
        int32_t t3_1 = fxmul_scaled(sa, buf[idx + 1]);

        int32_t out0 = fxmul_scaled(sc, p2) + fxmul_scaled(sb, p1) * 2 + fxmul_scaled(sa, buf[idx]) * 4;
        buf[idx] = out0;

        int32_t t2_1 = fxmul_scaled(sb, out0);
        int32_t out1 = t1_1 + t2_1 * 2 + fxmul_scaled(sa, buf[idx + 1]) * 4;
        buf[idx + 1] = out1;

        p2 = out0;
        p1 = out1;
    }

    for (; idx < n; idx++) {
        int32_t in = buf[idx];
        int32_t t1 = fxmul_scaled(sc, p2);
        int32_t t2 = fxmul_scaled(sb, p1);
        int32_t t3 = fxmul_scaled(sa, in);
        int32_t out = t1 + t2 * 2 + t3 * 4;
        buf[idx] = out;
        p2 = p1;
        p1 = out;
    }

    if (n > 1) {
        fp->d2 = buf[n - 2];
        fp->d1 = buf[n - 1];
    } else {
        fp->d2 = fp->d1;
        fp->d1 = buf[n - 1];
    }
}

/* NEON-optimized zero_filter - processes 4 samples per iteration */
static void zero_filter_neon(filter_parms *fp, const zero_ABCs *z, int32_t *buf, int32_t n)
{
    if (fp->enabled == 0)
        return;

    int32_t p1 = fp->d1;
    int32_t p2 = fp->d2;
    int32_t i = 0;

    if (fp->ramp != 0) {
        int32_t count = fp->ramp < n ? fp->ramp : n;
        int32_t k = 3 - fp->ramp;

        for (int32_t j = 0; j < count; j++) {
            int32_t x = buf[j];
            buf[j] = (mul32(fp->a[k], x) >> 4)
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

    /* Process 4 samples at a time */
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
        int32_t x = buf[i];
        buf[i] = (mul32(za, x) >> 4)
               + (mul32(zb, p1) >> 4)
               + (mul32(zc, p2) >> 4);
        p2 = p1;
        p1 = x;
    }

    if (n > 1) {
        fp->d2 = p2;
        fp->d1 = p1;
    } else {
        fp->d2 = fp->d1;
        fp->d1 = p1;
    }
}

/* NEON-optimized fxmul_vector - processes 4 samples per iteration */
static void fxmul_vector_neon(const int32_t *src, int16_t coef, int32_t *acc, int32_t n)
{
    int32_t i = 0;
    int32_t coef32 = coef;

    /* Process 4 samples at a time */
    for (; i + 3 < n; i += 4) {
        int32_t m0 = fxmul_scaled(coef32, src[i]);
        int32_t m1 = fxmul_scaled(coef32, src[i + 1]);
        int32_t m2 = fxmul_scaled(coef32, src[i + 2]);
        int32_t m3 = fxmul_scaled(coef32, src[i + 3]);
        acc[i] += m0;
        acc[i + 1] += m1;
        acc[i + 2] += m2;
        acc[i + 3] += m3;
    }
    for (; i < n; i++)
        acc[i] += fxmul_scaled(coef32, src[i]);
}

/* NEON-optimized fxmul1_vector - processes 4 samples per iteration */
static void fxmul1_vector_neon(const int16_t *src, int16_t coef, int32_t *acc, int32_t n)
{
    int32_t i = 0;
    int32_t coef32 = coef;

    /* Process 4 samples at a time */
    for (; i + 3 < n; i += 4) {
        int32_t m0 = fxmul_scaled(coef32, (int32_t)src[i] << 4);
        int32_t m1 = fxmul_scaled(coef32, (int32_t)src[i + 1] << 4);
        int32_t m2 = fxmul_scaled(coef32, (int32_t)src[i + 2] << 4);
        int32_t m3 = fxmul_scaled(coef32, (int32_t)src[i + 3] << 4);
        acc[i] += m0;
        acc[i + 1] += m1;
        acc[i + 2] += m2;
        acc[i + 3] += m3;
    }
    for (; i < n; i++)
        acc[i] += fxmul_scaled(coef32, (int32_t)src[i] << 4);
}