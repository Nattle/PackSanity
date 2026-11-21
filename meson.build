project('packsanity', 'c', version: '0.1.0', meson_version: '>=1.3.0',
  license: 'MIT', default_options: ['c_std=c99', 'warning_level=2', 'default_library=static'])

cc = meson.get_compiler('c')
pkg = import('pkgconfig')
lua_include = include_directories('native/vendor/lua-5.4.9')
public_include = include_directories('native/include')
lua_args = []
system_deps = []
if host_machine.system() == 'darwin'
  lua_args += '-DLUA_USE_MACOSX'
elif host_machine.system() in ['linux', 'freebsd', 'openbsd', 'netbsd', 'dragonfly']
  lua_args += '-DLUA_USE_LINUX'
  system_deps += [cc.find_library('m'), dependency('dl')]
endif

# Explicit sources keep upstream interpreter/compiler entry points out of the library.
lua_sources = files(
  'native/vendor/lua-5.4.9/lapi.c',
  'native/vendor/lua-5.4.9/lauxlib.c',
  'native/vendor/lua-5.4.9/lbaselib.c',
  'native/vendor/lua-5.4.9/lcode.c',
  'native/vendor/lua-5.4.9/lcorolib.c',
  'native/vendor/lua-5.4.9/lctype.c',
  'native/vendor/lua-5.4.9/ldblib.c',
  'native/vendor/lua-5.4.9/ldebug.c',
  'native/vendor/lua-5.4.9/ldo.c',
  'native/vendor/lua-5.4.9/ldump.c',
  'native/vendor/lua-5.4.9/lfunc.c',
  'native/vendor/lua-5.4.9/lgc.c',
  'native/vendor/lua-5.4.9/linit.c',
  'native/vendor/lua-5.4.9/liolib.c',
  'native/vendor/lua-5.4.9/llex.c',
  'native/vendor/lua-5.4.9/lmathlib.c',
  'native/vendor/lua-5.4.9/lmem.c',
  'native/vendor/lua-5.4.9/loadlib.c',
  'native/vendor/lua-5.4.9/lobject.c',
  'native/vendor/lua-5.4.9/lopcodes.c',
  'native/vendor/lua-5.4.9/loslib.c',
  'native/vendor/lua-5.4.9/lparser.c',
  'native/vendor/lua-5.4.9/lstate.c',
  'native/vendor/lua-5.4.9/lstring.c',
  'native/vendor/lua-5.4.9/lstrlib.c',
  'native/vendor/lua-5.4.9/ltable.c',
  'native/vendor/lua-5.4.9/ltablib.c',
  'native/vendor/lua-5.4.9/ltm.c',
  'native/vendor/lua-5.4.9/lundump.c',
  'native/vendor/lua-5.4.9/lutf8lib.c',
  'native/vendor/lua-5.4.9/lvm.c',
  'native/vendor/lua-5.4.9/lzio.c',
 )
lua54 = static_library('packsanity_lua54', lua_sources, include_directories: lua_include,
  c_args: lua_args, dependencies: system_deps, pic: true, install: true)
executable('packsanity-lua54', 'native/vendor/lua-5.4.9/lua.c',
  include_directories: lua_include, link_with: lua54, dependencies: system_deps, install: true)

lua_host = library('packsanity_lua', 'native/src/lua_host.c',
  include_directories: [public_include, lua_include], link_with: lua54,
  dependencies: system_deps, c_args: cc.get_supported_arguments('-Werror=return-type'),
  install: true)
lua_dep = declare_dependency(link_with: lua_host, include_directories: public_include,
  dependencies: system_deps)
meson.override_dependency('packsanity-lua', lua_dep)
pkg.generate(lua_host, filebase: 'packsanity-lua', name: 'PackSanity Lua',
  description: 'Bounded PopTracker Lua host', subdirs: '.')
lua_runner = executable('packsanity-lua', 'native/src/packsanity_lua.c',
  dependencies: lua_dep, install: true)

if get_option('jni')
  add_languages('java', native: false)
  jni = dependency('jni', modules: [])
  shared_library('packsanity_lua_jni', 'native/src/lua_jni.c',
    dependencies: [lua_dep, jni], install: true)
endif

if get_option('python_bridge')
  python = import('python').find_installation(get_option('python'))
  if python.language_version() != '3.13'
    error('The embedded bridge requires Python 3.13 development headers/library')
  endif
  # A locally built static libpython also needs the link flags in Libs.private.
  python_embed = python.dependency(embed: true, static: python.get_variable('Py_ENABLE_SHARED') == 0
    and python.get_variable('PYTHONFRAMEWORK', '') == '')
  python_host = shared_library('packsanity_python', 'native/src/python_host.c',
    include_directories: public_include, dependencies: python_embed, install: true)
  python_dep = declare_dependency(link_with: python_host, include_directories: public_include)
  meson.override_dependency('packsanity-python', python_dep)
  pkg.generate(python_host, filebase: 'packsanity-python', name: 'PackSanity Python',
    description: 'Embedded tracker-only APWorld JSON bridge', subdirs: '.')
  executable('packsanity-python', 'native/src/packsanity_python.c',
    dependencies: python_dep, install: true)
endif

install_headers('native/include/packsanity/lua_host.h',
  'native/include/packsanity/python_host.h', subdir: 'packsanity')
install_data('LICENSE', 'THIRD_PARTY_NOTICES.md',
  install_dir: get_option('datadir') / 'packsanity' / 'notices')
install_data('lua/host.lua', install_dir: get_option('datadir') / 'packsanity')

if get_option('tests')
  limits = executable('packsanity-native-limits', 'tests/native_limits.c', dependencies: lua_dep)
  test('native-limits', limits)
  test_python = find_program('python3')
  bootstrap = meson.current_source_dir() / 'lua/host.lua'
  fixture = meson.current_source_dir() / 'tests/fixtures/minimal'
  test('minimal-pack', test_python, args: [files('tools/test_pack.py'), fixture,
    '--host', lua_runner, '--bootstrap', bootstrap])
  test('zip-pack', test_python, args: [files('tests/test_runner.py'), fixture,
    lua_runner.full_path(), bootstrap])
  test('host-conformance', lua_runner, args: ['--bootstrap', bootstrap,
    '--execute', meson.current_source_dir() / 'tests/host_conformance.lua'])
endif
