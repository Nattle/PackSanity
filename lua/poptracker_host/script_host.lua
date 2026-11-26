ScriptHost = {}
function ScriptHost:CreateLuaItem(properties) return Tracker:CreateLuaItem(properties) end
function ScriptHost:AddWatchForCode(name, code, callback)
ScriptHost:RemoveWatchForCode(name)
if type(code) ~= 'string' or code == '' then return nil end
code_watchers[code] = code_watchers[code] or {}
return add_named_handler(code_watchers[code], name, callback)
end
function ScriptHost:RemoveWatchForCode(name)
local removed = false
for code, watchers in pairs(code_watchers) do
    if remove_named_handler(watchers, name) then removed = true end
    if next(watchers) == nil then code_watchers[code] = nil end
end
return removed
end
function ScriptHost:AddOnFrameHandler(name, callback) return add_named_handler(frame_handlers, name, callback) end
function ScriptHost:RemoveOnFrameHandler(name) return remove_named_handler(frame_handlers, name) end
function ScriptHost:AddOnLocationSectionChangedHandler(name, callback)
return add_named_handler(location_handlers, name, callback)
end
function ScriptHost:RemoveOnLocationSectionChangedHandler(name)
return remove_named_handler(location_handlers, name)
end
function ScriptHost:RemoveOnLocationSectionHandler(name)
return remove_named_handler(location_handlers, name)
end
function ScriptHost:AddMemoryWatch(_, _, _, _) return false end
function ScriptHost:RemoveMemoryWatch(_) return false end
-- Variable watches require a device/UAT provider. Keep the API callable until that optional
-- integration is installed, rather than failing a pack during initialisation.
function ScriptHost:AddVariableWatch(_, _, _, _) return false end
function ScriptHost:RemoveVariableWatch(_) return false end

local pack_modules, folded_pack_modules = {}, {}
local native_require = require
local loaded_modules = package.loaded

function PackSanityRegisterModule(path, source)
pack_modules[path] = source:gsub('^\239\187\191', '')
local key = definition_key(path)
local existing = folded_pack_modules[key]
if existing == nil or path < existing then folded_pack_modules[key] = path end
end

local function pack_module_path(path)
if pack_modules[path] ~= nil then return path end
return folded_pack_modules[definition_key(path)]
end

local function execute_pack_module(path, name)
local chunk, message = load(pack_modules[path], '@'..path, 't')
if chunk == nil then error(message, 0) end
return chunk(name)
end

local function require_path(name)
local path = name:gsub('%.', '/')
for _, prefix in ipairs({'scripts/', ''}) do
    for _, stem in ipairs({prefix..path, prefix..path..'/init'}) do
        local resolved = pack_modules[stem..'.LUA'] ~= nil and stem..'.LUA'
            or pack_module_path(stem..'.lua')
        if resolved ~= nil then return resolved end
    end
end
end

function require(name)
if type(name) ~= 'string' then return native_require(name) end
-- PopTracker's luasandbox_require also caches false and passes the requested name.
local result = loaded_modules[name]
if result ~= nil then return result end
local path = require_path(name)
if path == nil then return native_require(name) end
result = execute_pack_module(path, name)
if result == nil then result = true end
loaded_modules[name] = result
return result
end

local function relative_script_path(path)
-- PopTracker resolves root-prefixed script names inside the pack, never on the device.
while path:sub(1, 2) == './' do path = path:sub(3) end
return (path:gsub('^/+', ''))
end

local function preloaded_module_name(path)
local loaders = package.preload
local name = path:gsub('%.[lL][uU][aA]$', '')
if loaders[name] ~= nil then return name end
-- ZIP lookup in PopTracker is case-insensitive. Prefer exact spelling, then resolve only
-- registered pack modules; never add filesystem searchers or native module loading.
local folded, resolved = name:lower(), nil
for candidate in pairs(loaders) do
    if candidate:lower() == folded and (resolved == nil or candidate < resolved) then
        resolved = candidate
    end
end
return resolved or name
end

local function run_pack_script(path)
local relative = relative_script_path(path)
local resolved = pack_module_path(relative) or pack_module_path(relative..'.lua')
if resolved ~= nil then
    local name = resolved:gsub('^scripts/', ''):gsub('%.[lL][uU][aA]$', ''):gsub('/', '.')
    return pcall(execute_pack_module, resolved, name)
end
if next(pack_modules) ~= nil then return false, 'No such pack script: '..path end
-- Native callers can register their own Lua modules without the archive loader.
local name = preloaded_module_name(relative)
package.loaded[name] = nil
return pcall(native_require, name)
end

function ScriptHost:LoadScript(path)
local ok, result = run_pack_script(path)
if not ok then _G.__packsanity_last_load_error = tostring(result); return false end
return true
end

function ScriptHost:RunScriptAsync(path, arguments, callback)
local previous_arg = _G.arg
_G.arg = arguments
local ok, result = run_pack_script(path)
_G.arg = previous_arg
if type(callback) == 'function' then
    if ok then callback(result) else callback(nil) end
end
return ok
end
function ScriptHost:RunStringAsync(source, arguments, callback, progress)
local previous_arg = _G.arg
_G.arg = arguments
local chunk, load_error = load(source, '@packsanity/run_string_async.lua', 't')
local ok, result
if chunk ~= nil then ok, result = pcall(chunk) else ok, result = false, load_error end
_G.arg = previous_arg
if type(callback) == 'function' then callback(ok and result or nil) end
return ok
end
function ScriptHost:AsyncProgress(_) return false end

function PackSanityRunFrame(elapsed) run_handlers(frame_handlers, elapsed or 0) end

function PackSanityProviderCountChanged(code, count)
received_item_cache[code] = count ~= 0
run_handlers(code_watchers[code] or {}, code)
run_handlers(code_watchers['*'] or {}, code)
end

