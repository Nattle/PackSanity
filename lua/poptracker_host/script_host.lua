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

local pack_module_loaders, pack_module_aliases = {}, {}
local native_require = require
local loaded_modules = package.loaded
function PackSanityRegisterModule(path, loader) pack_module_loaders[path] = loader end
function PackSanityRegisterModuleAlias(alias, path) pack_module_aliases[alias] = path end

function require(name)
local path = pack_module_aliases[name]
if path == nil then return native_require(name) end
-- PopTracker's luasandbox_require also caches false and passes the requested name.
local result = loaded_modules[name]
if result ~= nil then return result end
result = pack_module_loaders[path](name)
if result == nil then result = true end
loaded_modules[name] = result
return result
end

local function module_name_for_path(path, loaders)
-- PopTracker resolves root-prefixed script names inside the pack, never on the device.
while path:sub(1, 2) == './' do path = path:sub(3) end
local name = path:gsub('^/+', ''):gsub('%.[lL][uU][aA]$', '')
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
local name = module_name_for_path(path, pack_module_loaders)
local loader = pack_module_loaders[name]
if loader ~= nil then
    return pcall(loader, (name:gsub('^scripts/', ''):gsub('/', '.')))
end
if next(pack_module_loaders) ~= nil then return false, 'No such pack script: '..path end
-- Native callers can register their own Lua modules without the archive loader.
name = module_name_for_path(path, package.preload)
package.loaded[name] = nil
return pcall(require, name)
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

