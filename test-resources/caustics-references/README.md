# Caustics Reference Images

Reference images and scenes for validating Progressive Photon Mapping (PPM) caustics implementation.

## BDPT Reference Scene (Primary Ground Truth)

The **primary reference** for caustics testing is:
- **Image:** `pbrt-reference.png` (317 KB, 1024 samples)
- **Scene:** `reference-scene.pbrt`

Rendered using PBRT v4's bidirectional path tracing (BDPT) integrator. This scene uses optimized parameters specifically chosen to produce clearly visible caustics.

### Scene Parameters

| Parameter | Value | Notes |
|-----------|-------|-------|
| **Camera position** | (0, 4, 8) | Higher and further back to see floor caustics |
| **Camera target** | (0, 0, 0) | Looking at sphere center |
| **Camera FOV** | 45° | Matches typical perspective |
| **Sphere center** | (0, 0, 0) | Origin |
| **Sphere radius** | 1.0 | Unit sphere |
| **Sphere IOR** | 1.5 | Standard glass |
| **Floor Y position** | -2.0 | 1 unit below sphere bottom |
| **Light position** | (0, 10, 0) | Directly above sphere |
| **Light intensity** | 500 (white) | Strong enough for bright caustics |
| **Resolution** | 800 × 600 | Standard test resolution |

### PBRT Rendering Settings

```pbrt
Integrator "bdpt"           # Bidirectional path tracing (required for caustics!)
  "integer maxdepth" 64     # Deep bounces for glass refraction

Sampler "zsobol"
  "integer pixelsamples" 1024  # ~214 second render time
```

**Critical:** Path tracing integrator (`"path"`) cannot efficiently render caustics. Always use `"bdpt"` or `"sppm"` for caustics scenes.

### Testing Our OptiX Implementation

To generate matching test images with our OptiX implementation:

```bash
cd /home/lene/workspace/menger

# Render with matching parameters
xvfb-run -a sbt "run --optix --sponge-type sphere --caustics \
  --color ffffff7f \
  --ior 1.5 \
  --radius 1.0 \
  --camera-pos 0,4,8 \
  --camera-lookat 0,0,0 \
  --camera-up 0,-1,0 \
  --light point:0,10,0:500 \
  --plane y:-2 \
  --plane-color cccccc \
  --timeout 5 \
  --save-name /tmp/menger-caustics-test.png"
```

**Alpha channel convention:**
- `--color ffffff7f`: Alpha = 0x7F (0.5) produces **glass** with refraction
- Alpha < 0.00392: Fully transparent (rays pass through, no refraction)
- Alpha 0.00392 - 0.99608: Glass/dielectric (refraction and caustics)
- Alpha ≥ 0.99608: Fully opaque solid

**Expected visual result:** Bright circular caustic pattern on the floor beneath the glass sphere, similar to `pbrt-reference.png`.

---

## Quick Start

1. **Render reference images** (requires PBRT v4, Mitsuba 3, or Appleseed):
   ```bash
   ./render-references.sh
   ```

2. **Copy reference to test resources**:
   ```bash
   cp output/canonical-caustics-pbrt.png ../../src/test/resources/caustics-reference.png
   ```

3. **Run validation tests**:
   ```bash
   sbt "testOnly *ReferenceMatchSpec"
   ```

## Directory Structure

```
caustics-references/
├── appleseed/                    # Appleseed Cornell box scene
│   └── cornell-box-caustics.appleseed
├── mitsuba/                      # Mitsuba tutorial
│   └── caustics_optimization.ipynb
├── pbrt/                         # PBRT v4 scenes
│   └── pbrt-v4-scenes/
│       ├── crown/                # Gems with caustics
│       ├── lte-orb/              # Spherical test scenes
│       └── transparent-machines/ # Complex glass
├── renders/                      # Our canonical scene
│   └── canonical-caustics.pbrt   # Primary reference scene
├── output/                       # Rendered images (gitignored)
├── render-references.sh          # Render script
└── README.md                     # This file
```

## Canonical Test Scene

The primary reference scene (`renders/canonical-caustics.pbrt`) matches the parameters defined
in the arc42 quality requirements:

| Parameter | Value |
|-----------|-------|
| Sphere center | (0, 0, 0) |
| Sphere radius | 1.0 |
| Sphere IOR | 1.5 |
| Floor position | Y = -2.0 |
| Light position | (0, 10, 0) |
| Camera position | (0, 1, 4) |
| Resolution | 800 × 600 |

**Expected Result:** Circular caustic centered at (0, -2, 0) with radius ~0.3 units.

## Renderer Installation

### PBRT v4 (Recommended)
```bash
git clone https://github.com/mmp/pbrt-v4.git
cd pbrt-v4
mkdir build && cd build
cmake ..
make -j$(nproc)
sudo make install
```

### Mitsuba 3
```bash
pip install mitsuba
```

### Appleseed
Download from https://appleseedhq.net/download.html

## Documentation

- [CAUSTICS_REFERENCES.md](../CAUSTICS_REFERENCES.md) - Full reference documentation
- [CAUSTICS_TEST_LADDER.md](../CAUSTICS_TEST_LADDER.md) - Test validation framework
- [arc42 Section 10](../../docs/arc42/10-quality-requirements.md) - Quality requirements

## Validation Workflow

```
1. Render reference with PBRT → canonical-caustics-reference.png
2. Render test image with our implementation → test-caustics.png
3. Compare using SSIM → should be > 0.90
```

## Troubleshooting

**"Reference image not found"**: Run `./render-references.sh pbrt` first.

**"SSIM too low"**: Check:
- IOR matches (should be 1.5)
- Light position matches
- Camera position matches
- Resolution matches

**"PBRT not found"**: Install PBRT v4 from source or use Mitsuba instead.
