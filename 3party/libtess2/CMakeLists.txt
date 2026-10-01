project(tess2)

include_directories(Include)

set(
  SRC
  Include/tesselator.h
  Source/bucketalloc.c
  Source/bucketalloc.h
  Source/dict.c
  Source/dict.h
  Source/geom.c
  Source/geom.h
  Source/mesh.c
  Source/mesh.h
  Source/priorityq.c
  Source/priorityq.h
  Source/sweep.c
  Source/sweep.h
  Source/tess.c
  Source/tess.h
)

add_library(${PROJECT_NAME} ${SRC})

if (NOT MSVC)
  # The sweep predicates rely on consistent floating-point comparisons for nearly coincident edges.
  target_compile_options(${PROJECT_NAME} PRIVATE -fno-fast-math -ffp-contract=off)
endif()
