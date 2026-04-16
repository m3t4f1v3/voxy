#ifndef _VOXY_LIGHTING_DECL
#define _VOXY_LIGHTING_DECL

vec2 getLightmapUv(uint index) {
    vec2 uv = vec2(float(index & 0xF0u), float((index & 0x0Fu) << 4u));
    return clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0));
}

#ifdef LIGHTING_SAMPLER_BINDING

layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;

vec4 getLighting(uint index) {
    return texture(lightSampler, getLightmapUv(index));
}
#endif

#endif
