#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec2 localPos;
flat in vec2 halfSize;
flat in vec4 radii;       // top-left, top-right, bottom-right, bottom-left
flat in float borderWidth;

out vec4 fragColor;

// Signed distance to a rounded box centred at the origin (y points down, like GUI coordinates).
float roundedBox(vec2 p, vec2 b, vec4 r) {
    float rad = p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

void main() {
    float d = roundedBox(localPos, halfSize, radii);
    float aa = max(fwidth(d), 0.0001); // one screen pixel, for anti-aliasing
    float coverage;
    if (borderWidth >= 0.0) {
        // Background: everything inside the inner border edge.
        coverage = clamp(0.5 - (d + borderWidth) / aa, 0.0, 1.0);
    } else {
        // Border ring between the outer edge and the inner edge.
        float outer = clamp(0.5 - d / aa, 0.0, 1.0);
        float inner = clamp(0.5 - (d - borderWidth) / aa, 0.0, 1.0);
        coverage = outer - inner;
    }
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * coverage);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
