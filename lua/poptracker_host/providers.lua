-- PotentialCodes is a declarative list on PopTracker custom items. Keep an index so bulk
-- hosted-item snapshots do not scan every custom item once for lookup and again for provider
-- counts. Items using CanProvideCodeFunc remain evaluated dynamically.
local function for_each_matching_custom_item(code, callback)
local indexed = custom_items_by_potential_code[code] or {}
local dynamic = custom_items_with_dynamic_codes
local indexed_index = 1
local dynamic_index = 1
while indexed_index <= #indexed or dynamic_index <= #dynamic do
    local indexed_item = indexed[indexed_index]
    local dynamic_item = dynamic[dynamic_index]
    if indexed_item ~= nil and
        (dynamic_item == nil or indexed_item.__packsanity_custom_item_order < dynamic_item.__packsanity_custom_item_order) then
        if callback(indexed_item) == false then return end
        indexed_index = indexed_index + 1
    elseif dynamic_item ~= nil then
        if custom_item_can_provide_code(dynamic_item, code) and callback(dynamic_item) == false then return end
        dynamic_index = dynamic_index + 1
    end
end
end

function Tracker:FindObjectForCode(code)
local exact = tracker_objects[code]
if exact ~= nil and not exact.__packsanity_location_placeholder then return exact end
local reference = find_location_reference(code)
if reference ~= nil then return reference end
local section = find_location_suffix(code)
if section ~= nil then return section end
if exact ~= nil then return exact end
local insensitive = tracker_objects[item_code_key(code)]
if insensitive ~= nil then return insensitive end
local found = nil
for_each_matching_custom_item(code, function(item)
    found = item
    return false
end)
if found ~= nil then return found end
for _, item in ipairs(custom_items) do
    if custom_item_can_provide_code(item, code) then
        index_custom_item(item)
        return item
    end
end
return find_tracker_object(code)
end

local function built_in_provider_count(object, code)
if object.Type == 'toggle' or object.Type == 'static' or object.Type == 'toggle_badged' then
    return object.Active and 1 or 0
end
if object.Type == 'composite_toggle' then
    for _, base_code in ipairs(object.__codes or {}) do
        if item_code_key(base_code) == item_code_key(code) then
            local stage = object.CurrentStage or 0
            return (stage & 1 ~= 0 and 1 or 0) + (stage & 2 ~= 0 and 1 or 0)
        end
    end
    return 0
end
if object.Type == 'consumable' then return object.AcquiredCount or 0 end
if object.Type == 'progressive' or object.Type == 'progressive_toggle' then
    if object.Type == 'progressive_toggle' and not object.Active then return 0 end
    if object.__allow_disabled and not object.Active then return 0 end
    local stage_index = (object.CurrentStage or 0) +
        ((object.Type == 'progressive' and object.__allow_disabled) and 0 or 1)
    for index = stage_index, 1, -1 do
        local stage = object.__stages[index]
        if stage == nil then break end
        for _, stage_code in ipairs(stage.codes) do
            if item_code_key(stage_code) == item_code_key(code) then return 1 end
        end
        if not stage.inherit then break end
    end
    return 0
end
return nil
end

function Tracker:ProviderCountForCode(code)
local key = item_code_key(code)
local host_count = __packsanity_provider_counts[code] or __packsanity_provider_counts[key]
if host_count ~= nil then return host_count end
local count = 0
for _, object in ipairs(item_objects_by_code[key] or {}) do
    count = count + (built_in_provider_count(object, code) or 0)
end
for_each_matching_custom_item(code, function(item)
    if type(item.ProvidesCodeFunc) == 'function' then
        local ok, provided_count = pcall(item.ProvidesCodeFunc, item, code)
        if ok and type(provided_count) == 'number' then count = count + provided_count end
    end
end)
return count
end

function Tracker:CreateLuaItem(properties)
local item = properties or {}
item.Type = item.Type or 'custom'
item.Name = item.Name or item.name or ''
item.Owner = item
item.ItemState = item.ItemState or {}
attach_overlay_methods(item)
if item.Name ~= '' then tracker_objects[item.Name] = item end
function item:Set(property, value)
    if type(property) ~= 'string' then return end
    self.ItemState = self.ItemState or {}
    local previous = self.ItemState[property]
    if previous == value then return end
    self.ItemState[property] = value
    if type(self.PropertyChangedFunc) == 'function' then
        local ok, error_message = pcall(self.PropertyChangedFunc, self, property, value)
        if not ok then
            table.insert(callback_errors, tostring(error_message))
            -- A failed observer must not be followed by another state-change notification.
            return
        end
    end
    notify_item_changed(self)
end
function item:Get(property)
    if type(property) ~= 'string' then return nil end
    return self.ItemState and self.ItemState[property]
end
table.insert(custom_items, item)
item.__packsanity_custom_item_order = #custom_items
-- Packs commonly assign these properties after CreateLuaItem. Keep them behind the metatable
-- so replacements also invalidate the index without requiring a FindObjectForCode side effect.
local provider_properties = {
    PotentialCodes = item.PotentialCodes,
    CanProvideCodeFunc = item.CanProvideCodeFunc,
}
rawset(item, 'PotentialCodes', nil)
rawset(item, 'CanProvideCodeFunc', nil)
local previous_metatable = getmetatable(item) or {}
local previous_index = previous_metatable.__index
local previous_newindex = previous_metatable.__newindex
local metatable = {}
for key, value in pairs(previous_metatable) do metatable[key] = value end
metatable.__index = function(target, key)
    if key == 'PotentialCodes' or key == 'CanProvideCodeFunc' then
        return provider_properties[key]
    end
    if type(previous_index) == 'function' then return previous_index(target, key) end
    if type(previous_index) == 'table' then return previous_index[key] end
end
metatable.__newindex = function(target, key, value)
    if key == 'PotentialCodes' or key == 'CanProvideCodeFunc' then
        provider_properties[key] = value
        index_custom_item(target)
    elseif type(previous_newindex) == 'function' then previous_newindex(target, key, value)
    elseif type(previous_newindex) == 'table' then previous_newindex[key] = value
    else rawset(target, key, value) end
end
setmetatable(item, metatable)
index_custom_item(item)
return item
end

function Tracker:UiHint(hint, value)
table.insert(ui_hints, tostring(hint)..'\30'..tostring(value or ''))
end

