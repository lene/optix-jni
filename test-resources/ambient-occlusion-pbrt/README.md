# Ambient occlusion against pbrt-v4 (optix-jni#106)

The default ambient fill of optix-jni is weighted by ambient occlusion: the share of
cosine-weighted hemisphere rays from the hit that escape within a radius (unlimited by default).
pbrt-v4's `ambientocclusion` integrator computes the same quantity, so it is the independent
reference ("analytic now, pbrt later").

| File | What |
|---|---|
| `sponge-on-floor.pbrt` | pbrt twin of the test scene: a level-1 Menger sponge (20 cubes of edge 1/3) on a floor at y = -0.5, 4096 samples per pixel, camera eye (-1.6, 1.4, 2.2) looking at (0, -0.1, 0), horizontal fov 60 |
| `render-pbrt-reference.sh` | renders the scene with a user-space pbrt-v4 build and writes the reference below (manual only) |
| `sponge-on-floor_400x300.pfm.gz` | the committed pbrt render, the factor per pixel (1 = open) |
| `sponge-on-floor_400x300.manifest.txt` | pbrt commit, binary hash, seed, scene hash |
| (`target/oj106-pbrt/`) | written by the suite, not committed: `ao-pbrt.png`, `ao-optix.png` (the two factors), `ao-error-x8.png` (the absolute error times 8, white = 0.125 or more), `ao-vs-pbrt.txt` |

`AmbientOcclusionPbrtSuite` renders the same scene in optix-jni (direct light off, every surface
white, so a pixel is 0.3 times the factor; 16 samples, 128 accumulated frames), reads the
factor back and compares it per pixel with the reference. Pixels at a geometric edge (5x5
neighbourhood spanning more than 0.15) are left out: pbrt averages over the pixel's area,
optix-jni takes one ray through its centre.

Result (110818 of 120000 pixels compared): mean absolute error 0.0135, 95th percentile 0.0337,
bias (optix-jni - pbrt) -0.0024. The remainder is the 8-bit quantisation of the optix-jni render
(a factor is known to 1/76.5), pbrt's own noise, and thin lines beside the sponge's edges on
the floor and between its faces, where the factor changes within a pixel (`ao-error-x8.png`).

## Building pbrt-v4 (no root)

```bash
git clone --recursive https://github.com/mmp/pbrt-v4 ~/src/pbrt-v4
cmake -S ~/src/pbrt-v4 -B ~/src/pbrt-v4/build -DCMAKE_BUILD_TYPE=Release
cmake --build ~/src/pbrt-v4/build -j
./render-pbrt-reference.sh      # PBRT_BIN=<dir with pbrt and imgtool> to use another build
```
