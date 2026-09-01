PopVersion = '0.36.0'

AccessibilityLevel = {
None = 0, Partial = 1, Inspect = 3, SequenceBreak = 5, Normal = 6, Cleared = 7,
}
Highlight = { Avoid = -1, None = 0, NoPriority = 1, Unspecified = 2, Priority = 3 }
ImageReference = {
FromPackRelativePath = function(_, path) return path end,
FromImageReference = function(_, reference, modifier)
    return tostring(reference or '')..tostring(modifier or '')
end,
}
received_item_cache = received_item_cache or {}
__packsanity_provider_counts = __packsanity_provider_counts or {}

local registered_paths = {}
local registered_load_events = {}
local tracker_objects = {}
local item_objects_by_code = {}
local custom_items = {}
local custom_items_by_potential_code = {}
local custom_items_with_dynamic_codes = {}
local composite_items = {}
local code_watchers = {}
local frame_handlers = {}
local location_handlers = {}
local ap_handlers = {
bounced = {}, clear = {}, item = {}, location = {}, retrieved = {}, scout = {}, setreply = {},
}
local ap_commands = {}
local ui_hints = {}
local callback_errors = {}

-- JSON item lookup treats item codes case-insensitively. Released packs may mix casing between
-- declarations and rules (for example, `Shadow_Medallion` vs `shadow_medallion`).
local function item_code_key(code)
if type(code) ~= 'string' then return code end
return string.lower(code)
end

local function add_named_handler(collection, name, callback)
if type(name) ~= 'string' or type(callback) ~= 'function' then return nil end
collection[name] = callback
return name
end

local function list_contains_identity(items, item)
for _, existing in ipairs(items) do
    if existing == item then return true end
end
return false
end

local function remove_named_handler(collection, name)
if collection[name] == nil then return false end
collection[name] = nil
return true
end

local function run_handlers(collection, ...)
local names = {}
for name, _ in pairs(collection) do table.insert(names, name) end
table.sort(names)
for _, name in ipairs(names) do
    local ok, error_message = pcall(collection[name], ...)
    if not ok then
        -- A broken optional autotracker must not take down the pack runtime.
        table.insert(callback_errors, tostring(error_message))
    end
end
end

local function register_paths(kind, ...)
for index = 1, select('#', ...) do
    local value = select(index, ...)
    if type(value) == 'table' then
        for _, path in ipairs(value) do
            registered_paths[path] = kind
            table.insert(registered_load_events, kind..'\30'..path)
        end
    elseif type(value) == 'string' then
        registered_paths[value] = kind
        table.insert(registered_load_events, kind..'\30'..value)
    end
end
end

local function attach_overlay_methods(object)
object.BadgeText = object.BadgeText or ''
object.BadgeTextColor = object.BadgeTextColor or ''
function object:SetOverlay(value) self.BadgeText = value == nil and '' or tostring(value) end
function object:SetOverlayColor(value) self.BadgeTextColor = value == nil and '' or tostring(value) end
function object:SetOverlayBackground(_) end
function object:SetOverlayAlign(_) end
function object:SetOverlayFontSize(_) end
end

local activate_staged_objects
local notify_item_changed
Tracker = {
ActiveVariantUID = 'standard',
BulkUpdate = false,
AllowDeferredLogicUpdate = false,
}
local function load_registered_paths(kind, ...)
register_paths(kind, ...)
local function activate(value)
    if type(value) == 'string' then activate_staged_objects(kind, value)
    elseif type(value) == 'table' then for _, path in ipairs(value) do activate(path) end end
end
for index = 1, select('#', ...) do
    activate(select(index, ...))
end
return true
end
function Tracker:AddItems(...) return load_registered_paths('items', ...) end
function Tracker:AddMaps(...) return load_registered_paths('maps', ...) end
function Tracker:AddLocations(...) return load_registered_paths('locations', ...) end
function Tracker:AddLayouts(...) return load_registered_paths('layouts', ...) end
function Tracker:AddClasses(...) return load_registered_paths('classes', ...) end
function Tracker:OpenLink(_, _) return false end

