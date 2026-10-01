#version 330

// Same uniform blocks as vanilla core/gui.vsh.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position; // z = shadow offset packed (see ShadowState)
in vec4 Color;
in vec2 UV0;      // position relative to the box center (GUI px)
in ivec2 UV1;     // box half size * 8
in ivec2 UV2;     // box radii * 2 packed: x = tl | tr << 8, y = br | bl << 8
in float LineWidth; // shadow parameters packed (see ShadowState); negative = inset

out vec4 vertexColor;
out vec2 localPos;
flat out vec2 halfSize;
flat out vec4 radii;
flat out vec2 offset;
flat out float sigma;
flat out float grow;
flat out float inset;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position.xy, 0.0, 1.0);
    vertexColor = Color;
    localPos = UV0;
    halfSize = vec2(UV1) / 8.0;
    int a = UV2.x & 0xFFFF;
    int b = UV2.y & 0xFFFF;
    radii = vec4(float(a & 0xFF), float((a >> 8) & 0xFF), float(b & 0xFF), float((b >> 8) & 0xFF)) / 2.0;
    int o = int(Position.z + 0.5);
    offset = vec2(float(o % 2048) - 1024.0, float(o / 2048) - 1024.0) / 4.0;
    inset = LineWidth < 0.0 ? 1.0 : 0.0;
    int p = int(abs(LineWidth) + 0.5) - 1;
    sigma = float(p % 256) / 2.0;
    grow = float(p / 256) / 2.0 - 128.0;
}
