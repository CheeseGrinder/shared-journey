#version 150

// Sharp bilinear sampling for the in-world waypoint icons (16x16 sprites
// drawn far above their native size when the player stands near a beacon).
//
// Plain bilinear blurs a magnified sprite, NEAREST makes uneven texel
// columns at non-integer ratios. Here the texture stays LINEAR-filtered
// but the sampling point is pushed to the texel center everywhere except
// in a band one SCREEN pixel wide around the texel edges: texel interiors
// stay flat and crisp, edges are smoothed over exactly one screen pixel.
// When a screen pixel covers more than a texel (minification) the band
// grows past the texel and this degrades to ordinary bilinear.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 texel = texCoord0 * size;
    vec2 texelBase = floor(texel);
    vec2 fromCenter = fract(texel) - 0.5;

    // Screen pixels per texel, per axis (fwidth keeps it correct when the
    // billboard is rotated); never below 1 so minification stays bilinear.
    vec2 scale = max(1.0 / max(fwidth(texel), vec2(1.0e-4)), vec2(1.0));
    vec2 flatRange = 0.5 - 0.5 / scale;
    vec2 blend = (fromCenter - clamp(fromCenter, -flatRange, flatRange)) * scale + 0.5;

    vec4 color = texture(Sampler0, (texelBase + blend) / size) * vertexColor;
    if (color.a < 0.1) {
        discard;
    }
    fragColor = color * ColorModulator;
}
