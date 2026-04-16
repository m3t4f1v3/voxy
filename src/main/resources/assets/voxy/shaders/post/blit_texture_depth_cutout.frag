#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
#endif
#endif

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip*2.0f-1.0f,1.0f);
    return view.xyz/view.w;
}
float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    bool hasDepth = depth != 0.0f && depth != 1.0f;
    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #else
    if (!hasDepth) {
        discard;
    }
    #endif

    vec3 point = vec3(0.0f);
    if (hasDepth) {
        point = rev3d(vec3(UV.xy, depth));
        depth = projDepth(point);
        depth = min(1.0f-(2.0f/((1<<24)-1)), depth);
        depth = depth * 0.5f + 0.5f;
        depth = gl_DepthRange.diff * depth + gl_DepthRange.near;
        gl_FragDepth = depth;
    } else {
        // Translucent water can intentionally skip depth writes to avoid self-
        // occluding other translucent walls. When that happens and there is only
        // sky behind the pixel, preserve the colour by compositing it at the far
        // plane instead of discarding it outright.
        gl_FragDepth = gl_DepthRange.far;
    }

    #ifdef EMIT_COLOUR
    #ifdef USE_ENV_FOG
    if (hasDepth && fogColour.a>0.0){
        float fogLerp = clamp(fma(length(point.xyz),endParams.x,endParams.y),0,endParams.z);//512 is 32*16 which is the render distance in blocks
        colour.rgb = mix(colour.rgb, fogColour.rgb, fogLerp*fogColour.a);
    }
    #endif
    #else
    colour = vec4(0);
    #endif

}
