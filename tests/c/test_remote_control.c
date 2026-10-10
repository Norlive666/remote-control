#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "remote_control.h"

static void seal(uint8_t f[20]) {
    uint16_t crc = 0xffff;
    for (int i = 2; i < 18; ++i) {
        crc ^= (uint16_t)f[i] << 8;
        for (int n = 0; n < 8; ++n)
            crc = (uint16_t)((crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1);
    }
    f[18] = (uint8_t)crc; f[19] = (uint8_t)(crc >> 8);
}
static void frame(uint8_t f[20], uint8_t seq, int16_t axis, uint16_t slider, uint8_t flags) {
    memset(f, 0, 20);
    f[0] = 0xa5; f[1] = 0x5a; f[2] = 1; f[3] = seq;
    f[4] = (uint8_t)axis; f[5] = (uint8_t)(axis >> 8);
    f[12] = (uint8_t)slider; f[13] = (uint8_t)(slider >> 8);
    f[16] = flags; seal(f);
}
static void feed(const uint8_t *f, unsigned n) { for (unsigned i=0;i<n;++i) RC_ProcessByte(f[i]); }
static RC_State_t state(void) { RC_State_t s; RC_GetState(&s); return s; }
static void ticks(unsigned n) { while (n--) RC_Tick1ms(); }
static void fresh_reset(void) { RC_Reset(); }

int main(void) {
    uint8_t f[20], g[20]; RC_State_t s;
    fresh_reset();
    /* Known CCITT-FALSE fixture: seq 1, axes +1000/-1000, slider 1000, enabled => 0x1f82. */
    const uint8_t golden[20] = {0xa5,0x5a,1,1,0xe8,3,0x18,0xfc,0,0,0,0,0xe8,3,0,0,1,0,0x82,0x1f};
    feed(golden, 7); assert(state().enabled == 0);
    feed(golden+7, 13); s=state(); assert(s.enabled && s.axes[0]==1000 && s.axes[1]==-1000 && s.sliders[0]==1000);
    frame(f,2,-1000,0,1); frame(g,3,0,1000,1); feed(f,20); feed(g,20);
    s=state(); assert(s.sequence==3 && s.enabled && s.axes[0]==0 && s.sliders[0]==1000);
    /* CRC, version, reserved, flags and value ranges reject without resetting deadline. */
    frame(f,4,1,1,1); f[4]^=1; feed(f,20);
    frame(f,4,1,1,1); f[2]=2; seal(f); feed(f,20);
    frame(f,4,1,1,1); f[17]=1; seal(f); feed(f,20);
    frame(f,4,1,1,0x80); feed(f,20);
    frame(f,4,1001,1,1); feed(f,20);
    frame(f,4,1,1001,1); feed(f,20);
    frame(f,4,0,0,1); f[10]=0x17; f[11]=0xfc; seal(f); feed(f,20); /* axis 3 = -1001 */
    frame(f,4,0,0,1); f[14]=0xe9; f[15]=0x03; seal(f); feed(f,20); /* slider 2 = 1001 */
    assert(state().sequence==3);
    ticks(299); assert(state().enabled);
    ticks(1); assert(!state().enabled && !state().axes[0] && !state().sliders[0]);
    /* Recovery needs a fresh disabled frame before enabling. */
    frame(f,200,1000,1000,1); feed(f,20); assert(!state().enabled);
    frame(f,200,1000,1000,0); feed(f,20); assert(!state().enabled);
    frame(f,201,1000,1000,1); feed(f,20); assert(!state().enabled);
    frame(f,202,0,0,0); feed(f,20); assert(state().sequence==202);
    frame(f,203,1000,1000,1); feed(f,20); assert(state().enabled && state().axes[0]==1000);
    frame(f,203,0,0,0); feed(f,20); ticks(299); assert(state().enabled);
    frame(f,100,0,0,0); feed(f,20); ticks(1); assert(!state().enabled);
    /* Sequence wraps 255 -> 0; duplicate and backwards packets remain stale. */
    fresh_reset(); frame(f,255,-1000,0,1); feed(f,20);
    frame(f,0,1000,1000,1); feed(f,20); assert(state().sequence==0 && state().axes[0]==1000);
    frame(f,255,-1000,0,1); feed(f,20); assert(state().axes[0]==1000);
    /* Nonzero controls with stop/disabled flags violate the wire protocol. */
    fresh_reset(); frame(f,10,1000,1000,1); feed(f,20); ticks(299);
    frame(f,11,1000,1000,3); feed(f,20);
    assert(state().enabled && state().sequence==10 && state().axes[0]==1000);
    frame(f,11,1000,1000,0); feed(f,20);
    assert(state().enabled && state().sequence==10 && state().axes[0]==1000);
    ticks(1); assert(!state().enabled && state().sequence==10);
    frame(f,11,0,0,2); feed(f,20); assert(state().stopped && state().sequence==11);
    frame(f,12,0,0,0); feed(f,20); assert(!state().enabled && !state().stopped && state().sequence==12);
    /* Independent Python binascii.crc_hqx fixture across every payload field. */
    fresh_reset();
    const uint8_t all_fields[20] = {0xa5,0x5a,1,255,0x18,0xfc,0xe8,3,0xff,0xff,0,0,0,0,0xe8,3,1,0,0xdc,0x37};
    feed(all_fields,20); s=state();
    assert(s.sequence==255 && s.enabled && s.axes[0]==-1000 && s.axes[1]==1000 && s.axes[2]==-1 && s.axes[3]==0 && s.sliders[0]==0 && s.sliders[1]==1000);
    /* Corrupted candidate containing another sync byte must resynchronize. */
    fresh_reset(); uint8_t noise[] = {0,0xa5,0,0xa5}; feed(noise,sizeof noise);
    frame(f,7,-1000,1000,1); feed(f,20); assert(state().sequence==7 && state().axes[0]==-1000);
    fresh_reset(); uint8_t prefix[12] = {0xa5,0x5a,9,9,9,9,9,9,9,9,9,9};
    feed(prefix,sizeof prefix); feed(f,20);
    assert(state().sequence==7 && state().axes[0]==-1000);
    puts("remote control behavior: pass");
    return 0;
}
