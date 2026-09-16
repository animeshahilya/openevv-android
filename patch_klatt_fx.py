#!/usr/bin/env python3
"""Apply NEON optimization guards to klatt_fx.c"""

import re

with open('/tmp/openevv-android/src/klatt/klatt_fx.c', 'r') as f:
    content = f.read()

# 1. Add NEON detection and KLATT_HAVE_NEON define at the top
old_header = '''#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "klatt_fx.h"
#include "klatt_tables.h"

/* The original relies on >> sign-extending negative operands, which C leaves
   implementation-defined. Every compiler we target does this; fail the build
   rather than produce silently wrong audio on one that does not. */
typedef char kfx_needs_arithmetic_shift[((int32_t)-8 >> 1) == -4 ? 1 : -1];'''

new_header = '''#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "klatt_fx.h"
#include "klatt_tables.h"

#if defined(__ARM_NEON__)
#include <arm_neon.h>
#define KLATT_HAVE_NEON 1
#elif defined(__SSE2__)
#include <emmintrin.h>
#define KLATT_HAVE_SSE2 1
#endif

/* The original relies on >> sign-extending negative operands, which C leaves
   implementation-defined. Every compiler we target does this; fail the build
   rather than produce silently wrong audio on one that does not. */
typedef char kfx_needs_arithmetic_shift[((int32_t)-8 >> 1) == -4 ? 1 : -1];'''

content = content.replace(old_header, new_header)

# 2. Wrap pole_filter with #ifndef KLATT_HAVE_NEON ... #endif
old_pole = '''void pole_filter(filter_parms *fp, int32_t *buf, int32_t n)
{
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
}'''

new_pole = '''#ifndef KLATT_HAVE_NEON
void pole_filter(filter_parms *fp, int32_t *buf, int32_t n)
{
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
#endif'''

content = content.replace(old_pole, new_pole)

# Now update parallel0_filter
old_parallel = '''/* The same resonator with no input term and no ramp: it runs purely on its
   own history, which is what the parallel branch wants when the excitation is
   summed in somewhere else. */
static void parallel0_filter(filter_parms *fp, int32_t *buf, int32_t n)
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
}'''

new_parallel = '''#ifndef KLATT_HAVE_NEON
/* The same resonator with no input term and no ramp: it runs purely on its
   own history, which is what the parallel branch wants when the excitation is
   summed in somewhere else. */
static void parallel0_filter(filter_parms *fp, int32_t *buf, int32_t n)
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
#endif'''

content = content.replace(old_parallel, new_parallel)

# Now update parallel0_filter in the NEON file - already done

with open('/tmp/openevv-android/src/klatt/klatt_fx.c', 'w') as f:
    f.write(content)

print("Done with first phase")