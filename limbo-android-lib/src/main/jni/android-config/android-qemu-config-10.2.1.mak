
#### QEMU version-specific options for 10.2.1

# QEMU 10.x is not using a stab lib
USE_QEMUSTAB ?= false

# For QEMU 10.x uses slirp as a static lib so set to true
USE_SLIRP_LIB ?= true

# For QEMU 10.x set the explicit sdlabi to false
USE_SDL_ABI ?= false

# For QEMU 2.11.0 and above (3.x, 4.x, 5.x, 6.x, 7.x, 8.x, 9.x, 10.x) disable these features
MISC += --disable-capstone
MISC += --disable-malloc-trim
