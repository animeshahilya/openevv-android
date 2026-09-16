#ifndef KLATT_FX_NEON_H
#define KLATT_FX_NEON_H

#include "klatt_fx.h"

#ifdef __cplusplus
extern "C" {
#endif

void pole_filter_neon(filter_parms *fp, int32_t *buf, int32_t n);
void zero_filter_neon(filter_parms *fp, const zero_ABCs *z, int32_t *buf, int32_t n);
void parallel0_filter_neon(filter_parms *fp, int32_t *buf, int32_t n);

#ifdef __cplusplus
}
#endif

#endif