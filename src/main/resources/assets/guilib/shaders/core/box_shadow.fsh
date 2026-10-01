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
flat in vec4 radii;   // top-left, top-right, bottom-right, bottom-left
flat in vec2 offset;
flat in float sigma;
flat in float grow;
flat in float inset;

out vec4 fragColor;

float cornerRadius(vec2 p, vec4 r) {
    return p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
}

// Signed distance to a rounded box centred at the origin (y points down).
float roundedBox(vec2 p, vec2 b, vec4 r) {
    float rad = cornerRadius(p, r);
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

float hard(float d) {
    return clamp(0.5 - d / max(fwidth(d), 0.0001), 0.0, 1.0);
}

// Gaussian-blurred rounded box, after Evan Wallace's "Fast Rounded Rectangle Shadows" (CC0).
float gaussian(float x, float s) {
    return exp(-(x * x) / (2.0 * s * s)) / (2.5066283 * s);
}

vec2 erf(vec2 x) {
    vec2 s = sign(x);
    vec2 a = abs(x);
    x = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    x *= x;
    return s - s / (x * x);
}

float shadowX(float x, float y, float s, float corner, vec2 hs) {
    float delta = min(hs.y - corner - abs(y), 0.0);
    float curved = hs.x - corner + sqrt(max(0.0, corner * corner - delta * delta));
    vec2 integral = 0.5 + 0.5 * erf((x + vec2(-curved, curved)) * (0.7071068 / s));
    return integral.y - integral.x;
}

float blurredBox(vec2 p, vec2 hs, float corner, float s) {
    float low = p.y - hs.y;
    float high = p.y + hs.y;
    float start = clamp(-3.0 * s, low, high);
    float end = clamp(3.0 * s, low, high);
    float dy = (end - start) / 4.0;
    float y = start + dy * 0.5;
    float value = 0.0;
    for (int i = 0; i < 4; i++) {
        value += shadowX(p.x, p.y - y, s, corner, hs) * gaussian(y, s) * dy;
        y += dy;
    }
    return value;
}

void main() {
    // The shadow shape: the box moved by the offset and grown by the spread (rounded corners grow with it).
    vec2 p = localPos - offset;
    vec2 hs = max(halfSize + grow, vec2(0.0));
    vec4 r = vec4(
        radii.x > 0.0 ? max(radii.x + grow, 0.0) : 0.0, radii.y > 0.0 ? max(radii.y + grow, 0.0) : 0.0,
        radii.z > 0.0 ? max(radii.z + grow, 0.0) : 0.0, radii.w > 0.0 ? max(radii.w + grow, 0.0) : 0.0);
    float shape;
    if (hs.x <= 0.0 || hs.y <= 0.0) {
        shape = 0.0;
    } else if (sigma < 0.25) {
        shape = hard(roundedBox(p, hs, r));
    } else {
        shape = blurredBox(p, hs, min(cornerRadius(p, r), min(hs.x, hs.y)), sigma);
    }
    float box = hard(roundedBox(localPos, halfSize, radii));
    // Outer shadows are never drawn under the box; inset shadows only inside it, around the shape.
    float coverage = inset > 0.5 ? box * (1.0 - shape) : shape * (1.0 - box);
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * coverage);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
