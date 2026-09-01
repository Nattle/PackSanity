ScriptHost = {}
function ScriptHost:CreateLuaItem(properties) return Tracker:CreateLuaItem(properties) end
function ScriptHost:AddWatchForCode(name, code, callback)
code_watchers[code] = code_watchers[code] or {}
return add_named_handler(code_watchers[code], name, callback)
end
function ScriptHost:RemoveWatchForCode(name)
local removed = false
for _, watchers in pairs(code_watchers) do
    if remove_named_handler(watchers, name) then removed = true end
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

local function module_name_for_path(path)
-- PopTracker resolves root-prefixed script names inside the pack, never on the device.
while path:sub(1, 2) == './' do path = path:sub(3) end
local name = path:gsub('^/+', ''):gsub('%.lua$', '')
if package.preload[name] ~= nil then return name end
-- ZIP lookup in PopTracker is case-insensitive. Prefer exact spelling, then resolve only
-- registered pack modules; never add filesystem searchers or native module loading.
local folded, resolved = name:lower(), nil
for candidate in pairs(package.preload) do
    if candidate:lower() == folded and (resolved == nil or candidate < resolved) then
        resolved = candidate
    end
end
return resolved or name
end

function ScriptHost:LoadScript(path)
local module_name = module_name_for_path(path)
package.loaded[module_name] = nil
local ok, result = pcall(require, module_name)
if not ok then _G.__packsanity_last_load_error = tostring(result); return false end
return result == nil and true or result
end

function ScriptHost:RunScriptAsync(path, arguments, callback)
local module_name = module_name_for_path(path)
local previous_arg = _G.arg
_G.arg = arguments
package.loaded[module_name] = nil
local ok, result = pcall(require, module_name)
_G.arg = previous_arg
if type(callback) == 'function' then callback(ok and result or nil) end
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

