project('mimalloc', 'c', default_options: [
  'c_std=c11',
  'warning_level=0',
  'default_library=static',
])

mimalloc_include = include_directories('include', 'src')

mimalloc_c_args = [
  '-DMI_MALLOC_OVERRIDE',
  '-DMI_STATIC_LIB',
  '-DMI_BUILD_RELEASE',
  '-DMI_CMAKE_BUILD_TYPE=release',
]

if host_machine.system() == 'darwin'
  mimalloc_c_args += [
    '-DMI_OSX_ZONE=1',
    '-DMI_OSX_INTERPOSE=1',
  ]
endif

if host_machine.system() == 'windows'
  mimalloc_c_args += ['-DMI_WIN_NOREDIRECT=1']
endif

mimalloc_lib = static_library(
  'mimalloc',
  files('src/static.c'),
  include_directories: mimalloc_include,
  c_args: mimalloc_c_args,
  dependencies: dependency('threads'),
  pic: true,
  install: false,
)

mimalloc_dep = declare_dependency(
  include_directories: mimalloc_include,
  link_whole: mimalloc_lib,
  dependencies: dependency('threads'),
)
