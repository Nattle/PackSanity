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

local function index_custom_item(item)
for _, code in ipairs(item.__packsanity_indexed_codes or {}) do
    local indexed = custom_items_by_potential_code[code] or {}
    for index = #indexed, 1, -1 do
        if indexed[index] == item then table.remove(indexed, index) end
    end
    if #indexed == 0 then custom_items_by_potential_code[code] = nil end
end
item.__packsanity_indexed_codes = {}
for index = #custom_items_with_dynamic_codes, 1, -1 do
    if custom_items_with_dynamic_codes[index] == item then
        table.remove(custom_items_with_dynamic_codes, index)
    end
end
if type(item.PotentialCodes) == 'table' then
    for _, code in ipairs(item.PotentialCodes) do
        if type(code) == 'string' then
            local indexed = custom_items_by_potential_code[code]
            if indexed == nil then
                indexed = {}
                custom_items_by_potential_code[code] = indexed
            end
            if not list_contains_identity(indexed, item) then
                table.insert(indexed, item)
                table.sort(indexed, function(left, right)
                    return left.__packsanity_custom_item_order < right.__packsanity_custom_item_order
                end)
            end
            table.insert(item.__packsanity_indexed_codes, code)
        end
    end
elseif type(item.CanProvideCodeFunc) == 'function' and
    not list_contains_identity(custom_items_with_dynamic_codes, item) then
    table.insert(custom_items_with_dynamic_codes, item)
    table.sort(custom_items_with_dynamic_codes, function(left, right)
        return left.__packsanity_custom_item_order < right.__packsanity_custom_item_order
    end)
end
end

local function find_tracker_object(id)
local object = tracker_objects[id]
if object ~= nil then return object end
for _, item in ipairs(custom_items) do
    if item.Name == id then
        tracker_objects[id] = item
        index_custom_item(item)
        return item
    end
end
return nil
end

local function sync_composite_state(object)
if object == nil or object.Type ~= 'composite_toggle' then return end
local left = find_tracker_object(object.__composite_left)
local right = find_tracker_object(object.__composite_right)
object.CurrentStage = (left ~= nil and left.Active and 1 or 0) +
    (right ~= nil and right.Active and 2 or 0)
object.Active = true
end

local function notify_item_watchers(object)
local notified = {}
local function notify(code)
    if type(code) == 'string' and not notified[code] then
        notified[code] = true
        run_handlers(code_watchers[code] or {}, code)
    end
end
for _, code in ipairs(object.__codes or {}) do notify(code) end
if object.Type ~= 'composite_toggle' then
    for _, stage in ipairs(object.__stages or {}) do
        for _, code in ipairs(stage.codes or {}) do notify(code) end
    end
end
local all_codes = {}
for _, code in ipairs(object.__codes or {}) do table.insert(all_codes, code) end
for _, stage in ipairs(object.__stages or {}) do
    for _, code in ipairs(stage.codes or {}) do table.insert(all_codes, code) end
end
run_handlers(code_watchers['*'] or {}, table.concat(all_codes, ', '))
end

notify_item_changed = function(object)
notify_item_watchers(object)
if object.Type == 'composite_toggle' then return end
for _, composite in ipairs(composite_items) do
    local stage = composite.CurrentStage or 0
    local updated = stage
    if find_tracker_object(composite.__composite_left) == object then
        if object.Active then updated = updated | 1 else updated = updated & ~1 end
    end
    if find_tracker_object(composite.__composite_right) == object then
        if object.Active then updated = updated | 2 else updated = updated & ~2 end
    end
    if updated ~= stage then
        composite.CurrentStage = updated
        composite.Active = true
        notify_item_watchers(composite)
    end
end
end

local change_seeded_item
change_seeded_item = function(object, action)
if object == nil then return false end
local changed = false
if object.Type == 'custom' then
    local callback = action == 'secondary' and object.OnRightClickFunc or object.OnLeftClickFunc
    if type(callback) == 'function' then
        local ok, error_message = pcall(callback, object)
        if not ok then table.insert(callback_errors, tostring(error_message)) end
        return ok
    end
elseif object.Type == 'toggle' then
    if action == 'primary' then
        changed = not object.Active
        object.Active = true
    elseif action == 'secondary' and object.__allow_disabled then
        changed = object.Active
        object.Active = false
    elseif action == 'single' and object.__allow_disabled then
        object.Active = not object.Active
        changed = true
    end
elseif object.Type == 'progressive' then
    local stages = #(object.__stages or {})
    if stages == 0 then return false end
    if object.__allow_disabled then
        if action == 'primary' then
            if not object.Active then
                object.Active = true; object.CurrentStage = 1; changed = true
            elseif object.CurrentStage < stages then
                object.CurrentStage = object.CurrentStage + 1; changed = true
            elseif object.__loop then
                object.Active = false; object.CurrentStage = 0; changed = true
            end
        elseif action == 'secondary' then
            if object.Active and object.CurrentStage <= 1 then
                object.Active = false; object.CurrentStage = 0; changed = true
            elseif object.CurrentStage > 1 then
                object.CurrentStage = object.CurrentStage - 1; changed = true
            elseif object.__loop then
                object.Active = true; object.CurrentStage = stages; changed = true
            end
        elseif action == 'single' then
            if not object.Active then
                object.Active = true; object.CurrentStage = 1
            elseif object.CurrentStage < stages then
                object.CurrentStage = object.CurrentStage + 1
            else
                object.Active = false; object.CurrentStage = 0
            end
            changed = true
        end
    elseif action == 'primary' then
        if object.CurrentStage < stages - 1 then
            object.CurrentStage = object.CurrentStage + 1; changed = true
        elseif object.__loop then object.CurrentStage = 0; changed = true end
    elseif action == 'secondary' then
        if object.CurrentStage > 0 then
            object.CurrentStage = object.CurrentStage - 1; changed = true
        elseif object.__loop then object.CurrentStage = stages - 1; changed = true end
    elseif action == 'single' then
        object.CurrentStage = (object.CurrentStage + 1) % stages; changed = true
    end
elseif object.Type == 'progressive_toggle' then
    if action == 'primary' then object.Active = not object.Active; changed = true
    elseif action == 'secondary' and #(object.__stages or {}) > 0 then
        object.CurrentStage = (object.CurrentStage + 1) % #(object.__stages or {}); changed = true
    elseif action == 'single' and #(object.__stages or {}) > 0 then
        if not object.Active then object.Active = true; object.CurrentStage = 0
        elseif object.CurrentStage < #(object.__stages or {}) - 1 then
            object.CurrentStage = object.CurrentStage + 1
        else object.Active = false; object.CurrentStage = 0 end
        changed = true
    end
elseif object.Type == 'consumable' then
    local next_count = object.AcquiredCount or 0
    if action == 'primary' then next_count = next_count + (object.Increment or 1)
    elseif action == 'secondary' then next_count = next_count - (object.Decrement or 1)
    elseif action == 'single' then
        next_count = next_count + 1
        if object.MaxCount >= object.MinCount and next_count > object.MaxCount then
            next_count = object.MinCount
        end
    end
    if object.MaxCount >= object.MinCount and next_count > object.MaxCount then next_count = object.MaxCount end
    if next_count < object.MinCount then next_count = object.MinCount end
    if next_count ~= object.AcquiredCount then
        object.AcquiredCount = next_count
        object.Active = next_count > 0
        changed = true
    end
elseif object.Type == 'composite_toggle' then
    local stage = object.CurrentStage or 0
    if action == 'primary' then object.CurrentStage = stage ~ 1; changed = true
    elseif action == 'secondary' then object.CurrentStage = stage ~ 2; changed = true end
    if changed then
        local left = find_tracker_object(object.__composite_left)
        local right = find_tracker_object(object.__composite_right)
        if left ~= nil and left.Type ~= 'composite_toggle' and left.Type ~= 'toggle_badged' then
            left.Active = object.CurrentStage & 1 ~= 0
            notify_item_changed(left)
        end
        if right ~= nil and right.Type ~= 'composite_toggle' and right.Type ~= 'toggle_badged' then
            right.Active = object.CurrentStage & 2 ~= 0
            notify_item_changed(right)
        end
    end
elseif object.Type == 'toggle_badged' then
    if action == 'secondary' then
        object.Active = not object.Active; changed = true
    elseif action == 'primary' then
        return change_seeded_item(find_tracker_object(object.__base_item), 'single')
    end
end
if changed then notify_item_changed(object) end
return changed
end

function PackSanityChangeItemState(item_id, action)
return change_seeded_item(find_tracker_object(item_id), action)
end

function PackSanityChangeLocationSectionState(section_id, action)
local section = tracker_objects[section_id]
if section == nil or section.Type ~= 'location' then return false end
local primary = action == 'primary'
if section.__clear_as_group then
    for _, code in ipairs(section.__hosted_codes or {}) do
        change_seeded_item(find_tracker_object(code), primary and 'primary' or 'secondary')
    end
end
local available = section.AvailableChestCount or section.ChestCount or 0
if primary then
    section.AvailableChestCount = math.max(0, available - 1)
else
    section.AvailableChestCount = math.min(section.ChestCount, available + 1)
end
return true
end

function PackSanityResetTrackerObjects()
local seen = {}
for _, object in pairs(tracker_objects) do
    if not seen[object] then
        seen[object] = true
        if object.Type == 'location' then
            object.Active = true
            object.AvailableChestCount = object.__initial_available_chest_count or object.ChestCount or 0
            object.__packsanity_reset_accessibility(
                object.__initial_accessibility_level or AccessibilityLevel.None
            )
            object.Highlight = object.__initial_highlight or Highlight.None
        elseif object.Type ~= 'custom' then
            object.Active = object.__initial_active == true
            object.CurrentStage = object.__initial_current_stage or 0
            object.AcquiredCount = object.__initial_acquired_count or 0
        end
    end
end
for _, object in ipairs(composite_items) do sync_composite_state(object) end
end

function PackSanityItemStates(ids)
local records = {}
for id in string.gmatch(ids or '', '[^\31]+') do
    local item = find_tracker_object(id)
    if item ~= nil then
        sync_composite_state(item)
        table.insert(records, id..'\30'..(item.Active and '1' or '0')..'\30'..
            tostring(item.CurrentStage or 0)..'\30'..tostring(item.AcquiredCount or 0))
    end
end
return table.concat(records, '\31')
end

-- Snapshot Lua-created items referenced by pack locations. Their names, icons and overlays may
-- change after Archipelago callbacks; JSON item definitions do not contain these objects.
function PackSanityHostedItems(codes)
local records = {}
local function safe_field(value)
    return (tostring(value or ''):gsub('[\30\31]', ' '))
end
for code in string.gmatch(codes or '', '[^\31]+') do
    local item = Tracker:FindObjectForCode(code)
    if item ~= nil and item.Type == 'custom' then
        table.insert(records, table.concat({
            safe_field(code), safe_field(item.Name), safe_field(item.Icon),
            safe_field(item.IconMods), safe_field(item.BadgeText),
            safe_field(Tracker:ProviderCountForCode(code)),
        }, '\30'))
    end
end
return table.concat(records, '\31')
end

function PackSanityLocationStates(ids)
local records = {}
for id in string.gmatch(ids or '', '[^\31]+') do
    local section = tracker_objects[id]
    if section ~= nil and section.Type == 'location' then
        table.insert(records, id..'\30'..tostring(section.ChestCount or 0)..'\30'..
            tostring(section.AvailableChestCount or 0)..'\30'..tostring(section.AccessibilityLevel or 0))
    end
end
return table.concat(records, '\31')
end

-- JSON locations are seeded by PackSanity rather than constructed by PopTracker's native
-- location loader. Mirror the access result calculated from their pack rules onto those
-- objects so Lua helpers that inspect another location's AccessibilityLevel (notably
-- ALttP's OWDungeonChecks summary-map helper) see the same state as the rendered marker.
function PackSanitySetLocationAccessibility(encoded)
local changed_ids = {}
for record in string.gmatch(encoded or '', '[^\29]+') do
    local id, level = string.match(record, '^([^\30]+)\30(-?%d+)$')
    local location = id and tracker_objects[id]
    if location ~= nil and location.Type == 'location' and
        not location.__packsanity_is_accessibility_pack_owned() then
        if location.__packsanity_sync_accessibility(tonumber(level) or AccessibilityLevel.None) then
            table.insert(changed_ids, id)
        end
    end
end
return table.concat(changed_ids, '\31')
end

-- Notify after the complete mirror has been applied, with a fresh host-call budget per section.
function PackSanityNotifyLocationAccessibility(id)
local location = tracker_objects[id]
if location ~= nil then run_handlers(location_handlers, location) end
end

local function custom_item_can_provide_code(item, code)
if type(item.PotentialCodes) == 'table' then
    for _, potential_code in ipairs(item.PotentialCodes) do
        if potential_code == code then return true end
    end
    return false
end
if type(item.CanProvideCodeFunc) == 'function' then
    local ok, can_provide = pcall(item.CanProvideCodeFunc, item, code)
    return ok and not not can_provide
end
return false
end

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
    self.ItemState[property] = value
    if previous ~= value and type(self.PropertyChangedFunc) == 'function' then
        local ok, error_message = pcall(self.PropertyChangedFunc, self, property, value)
        if not ok then
            table.insert(callback_errors, tostring(error_message))
            -- A failed observer must not be followed by another state-change notification.
            return
        end
    end
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

-- Sandboxed pack I/O. Files are registered from the selected pack/variant and no operation can
-- escape that in-memory table. It provides read-only access needed by pack data and mappings.
local pack_files, input_file = {}, nil
function PackSanityRegisterPackFile(path, contents)
if type(path) == 'string' and type(contents) == 'string' then pack_files[path] = contents end
end
local function pack_relative_path(path)
if type(path) ~= 'string' then return nil end
path = path:gsub('\\', '/'):gsub('^%./', ''):gsub('^/+', '')
if path == '..' or path:find('^%.%./') or path:find('/%.%./') or path:find('/%.%.$') then return nil end
return path
end
local function new_pack_file(data)
local file, cursor, closed = {}, 1, false
local function ensure_open() if closed then error('pack file is closed') end end
function file:close() if closed then return nil end; closed = true; return true end
function file:read(...)
    ensure_open()
    local formats, result = {...}, {}
    if #formats == 0 then formats = {'*l'} end
    for _, format in ipairs(formats) do
        if type(format) == 'number' then
            if format <= 0 then table.insert(result, '')
            elseif cursor > #data then table.insert(result, nil)
            else local value = data:sub(cursor, cursor + format - 1); cursor = cursor + #value; table.insert(result, value) end
        elseif format == '*a' or format == '*all' then
            local value = data:sub(cursor)
            -- PopTracker's long-form read leaves the file position unchanged.
            if format == '*a' then cursor = #data + 1 end
            table.insert(result, value)
        elseif format == '*l' or format == '*L' or format == '*line' then
            if cursor > #data then table.insert(result, nil) else
                local newline = data:find('\n', cursor, true)
                local ending = newline and (format == '*L' and newline or newline - 1) or #data
                local value = data:sub(cursor, ending):gsub('\r$', '')
                cursor = newline and newline + 1 or #data + 1
                table.insert(result, value)
            end
        elseif format == '*n' or format == '*number' then
            local start, finish, value = data:find('^%s*([+-]?%d*%.?%d+)', cursor)
            if value then cursor = finish + 1; table.insert(result, tonumber(value)) else table.insert(result, nil) end
        else error('unsupported pack-file read format: '..tostring(format)) end
    end
    return table.unpack(result)
end
function file:seek(whence, offset)
    ensure_open(); whence, offset = whence or 'cur', offset or 0
    local base = whence == 'set' and 1 or whence == 'end' and #data + 1 or cursor
    if whence ~= 'set' and whence ~= 'cur' and whence ~= 'end' then error('invalid pack-file seek origin') end
    cursor = math.max(1, base + offset); return cursor - 1
end
function file:lines(...)
    local formats = {...}
    return function() return file:read(table.unpack(formats)) end
end
return file
end
io = {}
function io.open(path, mode)
if mode ~= nil and mode ~= 'r' and mode ~= 'rb' then return nil, 'pack files are read-only', -1 end
local data = pack_files[pack_relative_path(path)]
if data == nil then return nil, 'pack file is unavailable', 2 end
return new_pack_file(data)
end
function io.input(value)
if value == nil then return input_file end
if type(value) == 'string' then input_file = assert(io.open(value)); return input_file end
input_file = value; return input_file
end
function io.read(...) if input_file == nil then error('no active pack file') end; return input_file:read(...) end
function io.lines(path, ...) local file = path and assert(io.open(path)) or input_file; if file == nil then error('no active pack file') end; return file:lines(...) end
local packsanity_epoch_seconds = 0
local function days_from_civil(year, month, day)
if month <= 2 then year = year - 1 end
local era = math.floor(year / 400)
local year_of_era = year - era * 400
local adjusted_month = month + (month > 2 and -3 or 9)
local day_of_year = math.floor((153 * adjusted_month + 2) / 5) + day - 1
local day_of_era = year_of_era * 365 + math.floor(year_of_era / 4) -
    math.floor(year_of_era / 100) + day_of_year
return era * 146097 + day_of_era - 719468
end
local function civil_from_days(days)
local shifted = days + 719468
local era = math.floor(shifted / 146097)
local day_of_era = shifted - era * 146097
local year_of_era = math.floor((day_of_era - math.floor(day_of_era / 1460) +
    math.floor(day_of_era / 36524) - math.floor(day_of_era / 146096)) / 365)
local year = year_of_era + era * 400
local day_of_year = day_of_era - (365 * year_of_era + math.floor(year_of_era / 4) -
    math.floor(year_of_era / 100))
local month_prime = math.floor((5 * day_of_year + 2) / 153)
local day = day_of_year - math.floor((153 * month_prime + 2) / 5) + 1
local month = month_prime + (month_prime < 10 and 3 or -9)
if month <= 2 then year = year + 1 end
return year, month, day
end
local function packsanity_os_time(value)
if value == nil then return packsanity_epoch_seconds end
if type(value) ~= 'table' then error("bad argument #1 to 'time' (table expected)") end
local days = days_from_civil(value.year, value.month, value.day)
return days * 86400 + (value.hour or 12) * 3600 + (value.min or 0) * 60 + (value.sec or 0)
end
local function packsanity_os_date(format, timestamp)
format = format or '%c'
timestamp = math.floor(timestamp or packsanity_epoch_seconds)
if format ~= '*t' and format ~= '!*t' then return '' end
local days = math.floor(timestamp / 86400)
local seconds = timestamp - days * 86400
local year, month, day = civil_from_days(days)
return {
    year = year, month = month, day = day,
    hour = math.floor(seconds / 3600),
    min = math.floor((seconds % 3600) / 60), sec = seconds % 60,
    wday = ((days + 4) % 7) + 1, yday = days - days_from_civil(year, 1, 1) + 1,
    isdst = false,
}
end
os = {
clock = function() return 0 end, difftime = function(a, b) return a - b end,
time = packsanity_os_time, date = packsanity_os_date,
}
-- Some packs explicitly require these standard modules. Keep require() pointed
-- at the sandboxed host tables instead of exposing Lua's filesystem libraries.
package.preload['io'] = function() return io end
package.preload['os'] = function() return os end
AutoTracker = {
ReadU8 = function(_) return 0 end, ReadU16 = function(_) return 0 end,
ReadU24 = function(_) return 0 end, ReadU32 = function(_) return 0 end,
ReadVariable = function(_) return 0 end,
AddMemoryWatch = function(_, _, _) return false end,
RemoveMemoryWatch = function(_) return false end,
GetConnectionState = function(self_or_backend, backend)
    backend = backend or self_or_backend
    if backend == 'AP' then return Archipelago.__connected and 3 or 0 end
    return 0
end,
}

Archipelago = {
PlayerNumber = -1, TeamNumber = -1, Seed = nil,
MissingLocations = {}, CheckedLocations = {},
ClientStatus = { UNKNOWN = 0, CONNECTED = 5, READY = 10, PLAYING = 20, GOAL = 30 },
__connected = false, __allows_location_checks = false, __allows_location_scouts = false,
__player_aliases = {}, __player_games = {},
__item_names = {}, __location_names = {},
}
function Archipelago:GetPlayerAlias(number)
return self.__player_aliases[number] or tostring(number)
end
function Archipelago:GetPlayerGame(number)
return self.__player_games[number] or ''
end
local function ap_name(names_by_game, id, game)
local selected_game = game or Archipelago:GetPlayerGame(Archipelago.PlayerNumber)
local names = names_by_game[selected_game] or {}
return names[id] or tostring(id)
end
function Archipelago:GetItemName(id, game) return ap_name(self.__item_names, id, game) end
function Archipelago:GetLocationName(id, game) return ap_name(self.__location_names, id, game) end
local function ap_add(kind, name, callback) return add_named_handler(ap_handlers[kind], name, callback) end
function Archipelago:AddBouncedHandler(name, callback) return ap_add('bounced', name, callback) end
function Archipelago:AddClearHandler(name, callback) return ap_add('clear', name, callback) end
function Archipelago:AddItemHandler(name, callback) return ap_add('item', name, callback) end
function Archipelago:AddLocationHandler(name, callback) return ap_add('location', name, callback) end
function Archipelago:AddRetrievedHandler(name, callback) return ap_add('retrieved', name, callback) end
function Archipelago:AddScoutHandler(name, callback) return ap_add('scout', name, callback) end
function Archipelago:AddSetReplyHandler(name, callback) return ap_add('setreply', name, callback) end
function Archipelago:Get(keys)
if type(keys) == 'string' then keys = { keys } end
for _, key in ipairs(keys or {}) do table.insert(ap_commands, 'get\30'..tostring(key)) end
end
function Archipelago:SetNotify(keys)
if type(keys) == 'string' then keys = { keys } end
for _, key in ipairs(keys or {}) do table.insert(ap_commands, 'notify\30'..tostring(key)) end
end
local function queue_location_command(kind, locations, create_as_hint)
local ids = {}
for _, location_id in ipairs(locations or {}) do
    if type(location_id) == 'number' then table.insert(ids, tostring(location_id)) end
end
if #ids == 0 then return false end
if kind == 'scouts' then
    local hint_mode = type(create_as_hint) == 'number' and math.floor(create_as_hint)
        or (create_as_hint == true and 1 or 0)
    table.insert(ap_commands, 'scouts\30'..hint_mode..','..table.concat(ids, ','))
else
    table.insert(ap_commands, 'checks\30'..table.concat(ids, ','))
end
return true
end
function Archipelago:LocationChecks(locations)
if not self.__allows_location_checks then return false end
return queue_location_command('checks', locations)
end
function Archipelago:LocationScouts(locations, create_as_hint)
if not self.__allows_location_scouts then return false end
return queue_location_command('scouts', locations, create_as_hint)
end
-- Hints are ephemeral chat commands, not durable location scouts. The mobile bridge
-- accepts them only for an authenticated slot; true means queued, not awarded by the server.
local function queue_hint(kind, value)
if not Archipelago.__connected then return false end
if type(value) ~= 'string' and type(value) ~= 'number' then return false end
local encoded = tostring(value):gsub('.', function(byte) return string.format('%02x', string.byte(byte)) end)
table.insert(ap_commands, kind..'\30'..encoded)
return true
end
function Archipelago:HintItem(item) return queue_hint('hint_item', item) end
function Archipelago:HintLocation(location) return queue_hint('hint_location', location) end
function PackSanityDiscardHintCommands(_)
local retained = {}
for _, command in ipairs(ap_commands) do
    if not command:match('^hint_') then table.insert(retained, command) end
end
ap_commands = retained
end
function Archipelago:StatusUpdate(status)
if type(status) ~= 'number' then return false end
table.insert(ap_commands, 'status\30'..math.floor(status))
return true
end
function Archipelago:PlayerNumberToName(number) return self:GetPlayerAlias(number) end
function Archipelago:ItemIndexToName(index) return self:GetItemName(index) end
function Archipelago:LocationIndexToName(index) return self:GetLocationName(index) end

function PackSanityHasArchipelagoItemHandlers(_)
return next(ap_handlers.clear) ~= nil and next(ap_handlers.item) ~= nil
end

function PackSanityDispatchClear(slot_data) run_handlers(ap_handlers.clear, slot_data or {}) end
function PackSanityDispatchItem(index, item_id, item_name, player_number)
run_handlers(ap_handlers.item, index, item_id, item_name, player_number)
end
function PackSanityDispatchLocation(location_id, location_name, replay)
if not replay then
    for index, value in ipairs(Archipelago.MissingLocations) do
        if value == location_id then table.remove(Archipelago.MissingLocations, index); break end
    end
    table.insert(Archipelago.CheckedLocations, location_id)
end
run_handlers(ap_handlers.location, location_id, location_name)
end
function PackSanityDispatchScout(location_id, location_name, item_id, item_name, player_number)
run_handlers(ap_handlers.scout, location_id, location_name, item_id, item_name, player_number)
end
function PackSanityDispatchRetrieved(key, value) run_handlers(ap_handlers.retrieved, key, value) end
function PackSanityDispatchSetReply(key, value, original_value)
run_handlers(ap_handlers.setreply, key, value, original_value)
end
function PackSanityDispatchBounced(packet) run_handlers(ap_handlers.bounced, packet) end

local function drain(queue)
local result = table.concat(queue, '\31')
for index = #queue, 1, -1 do queue[index] = nil end
return result
end
function PackSanityDrainArchipelagoCommands(_) return drain(ap_commands) end
function PackSanityDrainUiHints(_) return drain(ui_hints) end
function PackSanityDrainCallbackErrors(_) return drain(callback_errors) end

-- Optional PackSanity live-position contract. PopTracker itself does not consume this global;
-- keep the bridge defensive so ordinary packs remain completely unaffected.
function PackSanityLocationTrackingContract(_)
    local contract = LOCATION_TRACKING
    if type(contract) ~= 'table' or contract.api_version ~= 1
        or type(contract.location_setting_key) ~= 'string'
        or type(contract.location_icon_coords) ~= 'function' then
        return ''
    end
    return contract.location_setting_key
end

function PackSanityResolveLocationTracking(_)
    local contract = LOCATION_TRACKING
    if type(contract) ~= 'table' or contract.api_version ~= 1
        or type(contract.location_icon_coords) ~= 'function' then
        return ''
    end
    local ok, placement = pcall(contract.location_icon_coords, PackSanityLocationTrackingValue)
    if not ok or type(placement) ~= 'table' or placement.visible ~= true
        or type(placement.map) ~= 'string'
        or type(placement.x) ~= 'number' or type(placement.y) ~= 'number'
        or placement.x ~= placement.x or placement.y ~= placement.y
        or placement.x == math.huge or placement.x == -math.huge
        or placement.y == math.huge or placement.y == -math.huge then
        return ''
    end
    return placement.map .. '\30' .. tostring(placement.x) .. '\30' .. tostring(placement.y)
end

function PackSanityWasRegistered(path) return registered_paths[path] ~= nil end
function PackSanityRegisteredPaths(kind)
local paths = {}
for path, registered_kind in pairs(registered_paths) do
    if registered_kind == kind then table.insert(paths, path) end
end
table.sort(paths)
return table.concat(paths, '\31')
end
function PackSanityRegisteredLoadEvents(_) return table.concat(registered_load_events, '\31') end
function PackSanityRegisterPreloadedFile(kind, path)
if type(kind) == 'string' and type(path) == 'string' then register_paths(kind, path) end
end

function PackSanityLocationHighlights(_)
local records = {}
local seen = {}
for _, object in pairs(tracker_objects) do
    if object.Type == 'location' and object.FullID and not seen[object.FullID] then
        seen[object.FullID] = true
        table.insert(records, object.FullID..'\30'..tostring(object.Highlight or Highlight.None))
    end
end
table.sort(records)
return table.concat(records, '\31')
end

function PackSanityEvaluateRule(encoded)
local function invoke()
    local parts = {}
    for part in string.gmatch(encoded, '([^\31]+)') do table.insert(parts, part) end
    local function_name = table.remove(parts, 1)
    local callback = _G[function_name]
    if type(callback) ~= 'function' then
        error('Lua function was not found: '..tostring(function_name))
    end
    return callback(table.unpack(parts))
end
local ok, result = pcall(invoke)
if not ok then
    table.insert(callback_errors, tostring(result))
    return 0
end
return result
end

function PackSanityProviderCountForRule(code) return Tracker:ProviderCountForCode(code) end

function PackSanityRuleValues(encoded)
local records = {}
for record in string.gmatch(encoded or '', '[^\29]+') do
    local kind, key, value = string.match(record, '^([^\28]+)\28([^\28]+)\28(.*)$')
    local ok, result
    if kind == 'lua' then
        ok, result = pcall(PackSanityEvaluateRule, value)
    elseif kind == 'provider' then
        ok, result = pcall(PackSanityProviderCountForRule, value)
    end
    if ok then
        -- Lua access functions normally return booleans. Preserve the same PopTracker
        -- coercion used by the single-rule bridge instead of letting tonumber(true) turn a
        -- visible rule into zero in the bulk snapshot.
        local numeric_result
        if type(result) == 'boolean' then
            numeric_result = result and 1 or 0
        else
            numeric_result = tonumber(result) or 0
        end
        table.insert(records, key..'\28'..tostring(numeric_result))
    elseif result ~= nil then
        table.insert(callback_errors, tostring(result))
        table.insert(records, key..'\28'..'0')
    end
end
return table.concat(records, '\29')
end

local archipelago_item_types = {
toggle = true, static = true, progressive = true, progressive_toggle = true,
consumable = true, dynamic = true,
}

local function add_archipelago_item_code(codes, value)
if type(value) == 'string' then
    if value ~= '' and not archipelago_item_types[value] then codes[value] = true end
    return
end
if type(value) ~= 'table' then return end
local targets = value[1]
if type(targets) == 'table' then
    for _, target in ipairs(targets) do add_archipelago_item_code(codes, target) end
else
    add_archipelago_item_code(codes, targets)
end
end

function PackSanityArchipelagoItemCodes(_)
local codes = {}
for _, mapping in pairs(ITEM_MAPPING or {}) do add_archipelago_item_code(codes, mapping) end
local sorted = {}
for code, _ in pairs(codes) do table.insert(sorted, code) end
table.sort(sorted)
return table.concat(sorted, '\31')
end

function PackSanityIsSandboxed(global_name)
if global_name == 'io' then return io.open('__packsanity_sandbox_probe__', 'r') == nil end
if global_name == 'os.execute' then return os.execute == nil end
if global_name == 'package.loadlib' then return package.loadlib == nil end
if global_name == 'package.searchpath' then return package.searchpath == nil end
return _G[global_name] == nil
end
