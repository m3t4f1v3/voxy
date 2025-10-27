#version 330 core

layout(binding = 0) uniform sampler2D depthTex;

in vec2 UV;
void main() {
    gl_FragDepth = texture(depthTex, UV).r;
}