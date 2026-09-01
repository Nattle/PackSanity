function PackSanitySeedItem(
id, item_type, max_count, initially_active, allow_disabled,
initial_stage, initial_quantity, min_count, increment, decrement, icon, codes, stages,
loop, base_item, composite_left, composite_right
)
if item_type == 'toggle' or item_type == 'toggle_badged' or item_type == 'progressive_toggle' then
    allow_disabled = true
elseif item_type == 'static' then
    allow_disabled = false
end
if item_type == 'progressive_toggle' then loop = true end
local active = item_type == 'static' or item_type == 'composite_toggle' or
    not allow_disabled or initially_active or (item_type == 'consumable' and initial_quantity > 0)
local state = {
    Active = active,
    CurrentStage = (item_type == 'progressive' and allow_disabled and active)
        and initial_stage + 1 or initial_stage,
    AcquiredCount = initial_quantity,
    MinCount = min_count,
    MaxCount = max_count,
}
local object = {
    Type = item_type,
    Name = id,
    Increment = increment,
    Decrement = decrement,
    -- Host item icons are always represented as strings, including an unset icon.
    Icon = icon or '',
    Owner = nil,
    __codes = codes,
    __stages = stages,
    __allow_disabled = allow_disabled,
    __loop = loop == true,
    __base_item = base_item,
    __composite_left = composite_left,
    __composite_right = composite_right,
    __initial_active = active,
    __initial_current_stage = (item_type == 'progressive' and allow_disabled and active)
        and initial_stage + 1 or initial_stage,
    __initial_acquired_count = initial_quantity,
}
object.Owner = object
attach_overlay_methods(object)
local state_keys = {
    Active = true, CurrentStage = true, AcquiredCount = true, MinCount = true, MaxCount = true,
}
setmetatable(object, {
    __index = function(target, key)
        local value = state[key]
        if value ~= nil then return value end
        return rawget(target, key)
    end,
    __newindex = function(target, key, value)
        if not state_keys[key] then
            rawset(target, key, value)
            return
        end

        local changed = false
        if key == 'Active' then
            local next_active = not not value
            -- JsonItem treats integer assignments as enabled only above zero. Other Lua
            -- values keep ordinary truthiness, including floating-point zero.
            if math.type(value) == 'integer' then next_active = value > 0 end
            if not target.__allow_disabled then next_active = true end
            if target.Type == 'progressive' and target.__allow_disabled and not next_active then
                if state.CurrentStage ~= 0 then state.CurrentStage = 0; changed = true end
            elseif target.Type == 'progressive' and target.__allow_disabled and next_active and
                state.CurrentStage == 0 then
                state.CurrentStage = 1
                changed = true
            end
            if state.Active ~= next_active then state.Active = next_active; changed = true end
        elseif key == 'CurrentStage' then
            local next_stage = math.max(0, math.floor(tonumber(value) or 0))
            local stages = #(target.__stages or {})
            if stages > 0 then
                local maximum = target.Type == 'progressive' and target.__allow_disabled and stages or stages - 1
                next_stage = math.min(next_stage, maximum)
            end
            if target.Type == 'progressive' and target.__allow_disabled then
                local next_active = next_stage > 0
                if state.Active ~= next_active then state.Active = next_active; changed = true end
            end
            if state.CurrentStage ~= next_stage then state.CurrentStage = next_stage; changed = true end
        elseif key == 'AcquiredCount' then
            local next_count = math.floor(tonumber(value) or 0)
            if state.MaxCount >= state.MinCount and next_count > state.MaxCount then next_count = state.MaxCount end
            if next_count < state.MinCount then next_count = state.MinCount end
            if state.AcquiredCount ~= next_count then state.AcquiredCount = next_count; changed = true end
            if target.Type == 'consumable' then
                local next_active = next_count > 0
                if state.Active ~= next_active then state.Active = next_active; changed = true end
            end
        elseif key == 'MinCount' then
            local next_minimum = math.floor(tonumber(value) or 0)
            if state.MinCount ~= next_minimum then state.MinCount = next_minimum; changed = true end
            if state.AcquiredCount < next_minimum then state.AcquiredCount = next_minimum; changed = true end
        elseif key == 'MaxCount' then
            local next_maximum = math.floor(tonumber(value) or -1)
            if state.MaxCount ~= next_maximum then state.MaxCount = next_maximum; changed = true end
        end
        if changed and notify_item_changed ~= nil then notify_item_changed(target) end
    end,
})
tracker_objects[id] = object
if item_type == 'composite_toggle' then table.insert(composite_items, object) end
local function register_code(code)
    if type(code) ~= 'string' then return end
    local key = item_code_key(code)
    tracker_objects[code] = tracker_objects[code] or object
    tracker_objects[key] = tracker_objects[key] or object
    item_objects_by_code[key] = item_objects_by_code[key] or {}
    for _, registered in ipairs(item_objects_by_code[key]) do
        if registered == object then return end
    end
    table.insert(item_objects_by_code[key], object)
end
for _, code in ipairs(codes) do register_code(code) end
if item_type ~= 'composite_toggle' then
    for _, stage in ipairs(stages) do
        for _, code in ipairs(stage.codes) do register_code(code) end
    end
end
end

-- Section lookups may omit leading parent locations. Keep suffix identities separate
-- from exact aliases so an exact path always wins and re-seeding updates shared state.
local location_parents = {}
local location_sections = {}
local function find_location_suffix(code)
if type(code) ~= 'string' then return nil end
local parent, section = string.match(code, '^@(.+)/([^/]*)$')
if parent == nil then return nil end
local sections = location_sections[parent]
if sections == nil then
    for _, candidate in ipairs(location_parents) do
        if candidate == parent or string.sub(candidate, -(#parent + 1)) == '/'..parent then
            sections = location_sections[candidate]
            break
        end
    end
end
local full_id = sections ~= nil and sections[section] or nil
return full_id ~= nil and tracker_objects[full_id] or nil
end

function PackSanitySeedLocation(full_id, chest_count, aliases, clear_as_group, hosted_codes, accessibility_level, is_placeholder, is_parent)
local object = {
    Type = 'location', FullID = full_id, ChestCount = chest_count,
    __packsanity_location_placeholder = is_placeholder == true,
    __clear_as_group = clear_as_group == true,
    __hosted_codes = hosted_codes or {},
    __initial_available_chest_count = chest_count,
    -- Locations have no accessibility until its logic engine has evaluated them.
    -- Starting synthetic sections as Normal lets packs which consult another section's
    -- AccessibilityLevel (such as ALttP's dungeon graph) treat locked checks as open.
    __initial_accessibility_level = accessibility_level or AccessibilityLevel.None,
    __initial_highlight = Highlight.None,
}
local state = {
    Active = true,
    AvailableChestCount = chest_count,
    AccessibilityLevel = accessibility_level or AccessibilityLevel.None,
    Highlight = Highlight.None,
}
local accessibility_is_pack_owned = false
local state_keys = {
    Active = true, AvailableChestCount = true, AccessibilityLevel = true, Highlight = true,
}
setmetatable(object, {
    __index = function(target, key)
        local value = state[key]
        if value ~= nil then return value end
        return rawget(target, key)
    end,
    __newindex = function(target, key, value)
        if state_keys[key] then
            local previous = state[key]
            state[key] = value
            if key == 'AccessibilityLevel' then accessibility_is_pack_owned = true end
            if previous ~= value then run_handlers(location_handlers, target) end
        else
            rawset(target, key, value)
        end
    end,
})
-- Keep the fallback computed from parsed JSON rules separate from a value a pack itself sets.
-- While core normally owns this field; a Lua pack is still allowed to take ownership.
object.__packsanity_set_accessibility = function(value)
    local previous = state.AccessibilityLevel
    state.AccessibilityLevel = value
    if previous ~= value then run_handlers(location_handlers, object) end
end
-- Host-computed accessibility is mirrored in bulk. Do not invoke pack change handlers once per
-- parsed section; packs may perform broad tracker scans in those callbacks.
object.__packsanity_sync_accessibility = function(value)
    local changed = state.AccessibilityLevel ~= value
    state.AccessibilityLevel = value
    return changed
end
object.__packsanity_reset_accessibility = function(value)
    accessibility_is_pack_owned = false
    object.__packsanity_set_accessibility(value)
end
object.__packsanity_is_accessibility_pack_owned = function() return accessibility_is_pack_owned end
object.Owner = object
tracker_objects[full_id] = object
for _, alias in ipairs(aliases or {}) do
    tracker_objects[alias] = object
    if not is_placeholder then
        -- Register location identities even when they contain no sections. A sectionless
        -- exact/first suffix parent must stop lookup before another parent's sections.
        local parent, section
        if is_parent then parent = string.match(alias, '^@(.+)$')
        else parent, section = string.match(alias, '^@(.+)/([^/]*)$') end
        if parent ~= nil then
            if location_sections[parent] == nil then
                location_sections[parent] = {}
                table.insert(location_parents, parent)
            end
            if section ~= nil then location_sections[parent][section] = full_id end
        end
    end
end
end

function PackSanitySeedLocationAlias(alias)
if tracker_objects[alias] == nil and find_location_suffix(alias) == nil then
    PackSanitySeedLocation(alias, 0, { alias }, nil, nil, nil, true)
end
end

local staged_objects = { items = {}, locations = {} }
local function stage_object(kind, path, activate)
if type(path) ~= 'string' then activate(); return end
staged_objects[kind][path] = staged_objects[kind][path] or {}
table.insert(staged_objects[kind][path], activate)
end
activate_staged_objects = function(kind, path)
local objects = staged_objects[kind] and staged_objects[kind][path]
if objects == nil then return end
staged_objects[kind][path] = nil
for _, activate in ipairs(objects) do activate() end
end
function PackSanityStageItem(path, ...)
local arguments = {...}
stage_object('items', path, function() PackSanitySeedItem(table.unpack(arguments)) end)
end
function PackSanityStageLocation(path, ...)
local arguments = table.pack(...)
stage_object('locations', path, function() PackSanitySeedLocation(table.unpack(arguments, 1, arguments.n)) end)
end

