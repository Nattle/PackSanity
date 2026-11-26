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
item_objects_by_id[id] = object
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
local location_references = {}
local registered_locations = {}
local location_registration_order = 0
local location_batch_start = 0
local locations_by_path, registered_location_paths = {}, {}
local relocated_sections = {}
local references_by_location = {}

function PackSanityLocationParent(path)
local entry = locations_by_path[path]
if entry and entry.order <= location_batch_start then return path..'/', entry.id end
for _, candidate in ipairs(registered_location_paths) do
    entry = locations_by_path[candidate]
    if entry.order > location_batch_start then break end
    if candidate:sub(-#path) == path then return candidate..'/', entry.id end
end
return '', nil
end

local function registered_section(id)
local object = tracker_objects[id]
if object and object.Type == 'location' and not object.__packsanity_location_placeholder then return object end
end

local function location_order_path(section)
local path, location = {}, registered_locations[section.__packsanity_parent_location]
while location do
    table.insert(path, location)
    location = registered_locations[location.parent]
end
return path
end

-- Follow the registered tree's depth-first order when two sections match equally well.
-- Each location keeps its declared children ahead of children attached from other records.
local function earlier_section(section, current)
local left, right = location_order_path(section), location_order_path(current)
for depth = 1, math.min(#left, #right) do
    local a, b = left[#left + 1 - depth], right[#right + 1 - depth]
    if a ~= b then
        if depth > 1 and a.relocated ~= b.relocated then return not a.relocated end
        return a.order < b.order
    end
end
return #left < #right
end

local function reference_path(path)
return path:lower():gsub('^%s*', ''):gsub('%s*$', ''):gsub('^@?/*', ''):gsub('/+$', '')
end

local function section_match(path, target)
if path == target then return 1 end
if path:sub(-(#target + 1)) == '/'..target then return 2 end
end

local function resolve_section_reference(reference)
local exact, suffix, normalized
local ambiguous_normalized = false
local target = reference_path(reference.target)
local normalized_target = target:gsub(',', '')
local function consider(object, match)
    if match == 1 then
        if not exact or earlier_section(object, exact) then exact = object end
    elseif match == 2 then
        if not suffix or earlier_section(object, suffix) then suffix = object end
    elseif match == 3 and normalized ~= object then
        ambiguous_normalized = normalized ~= nil
        normalized = object
    end
end
for rank, group in ipairs({ reference.exact, reference.suffix, reference.normalized }) do
    for _, id in ipairs(group) do
        local object = registered_section(id)
        if object and not object.__packsanity_relocated then consider(object, rank) end
    end
end
for _, object in ipairs(relocated_sections) do
    local path = reference_path(object.__packsanity_named_path)
    local match = section_match(path, target)
    if not match and section_match(path:gsub(',', ''), normalized_target) then match = 3 end
    consider(object, match)
end
return exact or suffix or (not ambiguous_normalized and normalized) or reference.fallback
end

local function find_location_reference(alias)
if type(alias) ~= 'string' then return nil end
local references = location_references[alias]
for i = #(references or {}), 1, -1 do
    local entry = references[i]
    local object = resolve_section_reference(entry.reference)
    if not entry.ids then return object end
    for _, id in ipairs(entry.ids) do
        if object.__packsanity_location_id == id then return object end
    end
end
local parent, section = alias:match('^(@.+)/([^/]+)$')
for _, reference in ipairs(references_by_location[parent] or {}) do
    local object = resolve_section_reference(reference)
    if object.__packsanity_section_name == section then return object end
end
end

local function remember_location_alias(alias, id, is_parent)
local parent, section
if is_parent then parent = string.match(alias, '^@(.+)$')
else parent, section = string.match(alias, '^@(.+)/([^/]*)$') end
if parent == nil then return end
if location_sections[parent] == nil then
    location_sections[parent] = {}
    table.insert(location_parents, parent)
end
if section ~= nil then location_sections[parent][section] = id end
end

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
local key = sections ~= nil and sections[section] or nil
if key == nil then return nil end
return registered_section(key) or find_location_reference(key) or tracker_objects[key]
end

function PackSanitySeedLocation(full_id, chest_count, aliases, clear_as_group, hosted_codes, accessibility_level, is_placeholder, is_parent, detached, parent_id, relocated, relocated_section, section_name)
if is_parent and not is_placeholder then
    location_registration_order = location_registration_order + 1
    local location = { id = full_id, parent = parent_id, order = location_registration_order, relocated = relocated == true }
    registered_locations[full_id] = location
    local path = aliases and aliases[1] and aliases[1]:sub(2)
    if path and not locations_by_path[path] then
        locations_by_path[path] = location
        table.insert(registered_location_paths, path)
    end
end
local section_path = not is_parent and not is_placeholder and aliases and aliases[1]
local object = {
    Type = 'location', FullID = section_path and section_path:gsub('^@', '') or full_id,
    ChestCount = chest_count,
    __packsanity_location_id = full_id,
    __packsanity_parent_location = parent_id,
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
if relocated_section then
    object.__packsanity_relocated = true
    object.__packsanity_named_path = aliases[#aliases]
    object.__packsanity_section_name = section_name
    table.insert(relocated_sections, object)
end
if not detached then tracker_objects[full_id] = object end
for _, alias in ipairs(aliases or {}) do
    tracker_objects[alias] = object
    if not is_placeholder then
        -- Register location identities even when they contain no sections. A sectionless
        -- exact/first suffix parent must stop lookup before another parent's sections.
        remember_location_alias(alias, full_id, is_parent)
    end
end
return object
end

function PackSanitySeedLocationReference(full_id, chest_count, clear_as_group, hosted_codes, aliases, target_alias, exact, suffix, normalized, name_aliases, location_alias)
local fallback = PackSanitySeedLocation(full_id, chest_count, nil, clear_as_group, hosted_codes, nil, true, nil, true)
tracker_objects[full_id] = tracker_objects[full_id] or fallback
local reference = { exact = exact, suffix = suffix, normalized = normalized, fallback = fallback, target = target_alias }
if location_alias then
    references_by_location[location_alias] = references_by_location[location_alias] or {}
    table.insert(references_by_location[location_alias], reference)
end
local function remember(alias, ids)
    if not location_references[alias] then location_references[alias] = {} end
    table.insert(location_references[alias], { reference = reference, ids = ids })
end
remember(full_id)
remember(target_alias)
for _, alias in ipairs(aliases) do
    remember(alias)
    remember_location_alias(alias, alias)
end
for alias, ids in pairs(name_aliases) do
    remember(alias, ids)
    remember_location_alias(alias, alias)
end
end

function PackSanitySeedLocationAlias(alias)
if tracker_objects[alias] == nil and find_location_suffix(alias) == nil then
    PackSanitySeedLocation(alias, 0, { alias }, nil, nil, nil, true)
end
end

local staged_objects = { items = {}, locations = {} }
local item_registrations = {}
local folded_objects = { items = {}, locations = {} }
local definition_layers, definition_variant, definition_shapes
local ascii_case_keys = {}
for byte = 65, 90 do ascii_case_keys[string.char(byte)] = string.char(byte + 32) end
local definition_case_keys = ascii_case_keys
local function definition_key(path)
return (path:gsub(utf8.charpattern, definition_case_keys))
end
function PackSanitySetDefinitionCaseKeys(keys)
definition_case_keys = setmetatable(keys, { __index = ascii_case_keys })
end
function PackSanitySetDefinitionPaths(variant, layers, shapes)
definition_variant = variant ~= '' and variant or nil
definition_layers, definition_shapes = layers, shapes
end
local function definition_path(path)
if type(path) ~= 'string' or path:sub(1, 2) == '//' then return nil end
return path:gsub('^/', ''):gsub('\\', '/'):gsub('/+', '/'):match('^%s*(.-)%s*$')
end
local function archive_definition_path(path)
if not definition_layers then return nil end
local variant_path = definition_variant and definition_variant..'/'..path
for _, layer in ipairs(definition_layers) do
    local exact, folded = layer[1], layer[2]
    if variant_path then
        if exact[variant_path] then return variant_path end
        local variant_match = folded[definition_key(variant_path)]
        if variant_match then return variant_match end
    end
    if exact[path] then return path end
    local match = folded[definition_key(path)]
    if match then return match end
end
end
local function stage_object(kind, path, activate)
if type(path) ~= 'string' then activate(1, 1); return end
path = definition_path(path)
if path == nil then return end
staged_objects[kind][path] = staged_objects[kind][path] or {}
table.insert(staged_objects[kind][path], activate)
local key = definition_key(path)
if folded_objects[kind][key] == nil then folded_objects[kind][key] = path end
end
function PackSanityStageDefinitions(kind, path, activate)
if staged_objects[kind] and type(activate) == 'function' then stage_object(kind, path, activate) end
end
activate_staged_objects = function(kind, path)
local definitions = staged_objects[kind]
path = definition_path(path)
if path == nil then return false end
local archive_path = archive_definition_path(path)
if definition_layers and not archive_path then return false end
if definition_shapes then
    local expects_array = kind == 'items' or kind == 'locations' or kind == 'maps'
    if definition_shapes[archive_path] ~= expects_array then return false end
end
if definitions == nil then return true end
if archive_path then path = archive_path end
local objects = definitions[path]
if objects == nil and not definition_layers then
    local match = folded_objects[kind][definition_key(path)]
    if match ~= nil then path, objects = match, definitions[match] end
end
if objects == nil then return true end
local registration = 1
if kind == 'items' then
    registration = (item_registrations[path] or 0) + 1
    item_registrations[path] = registration
else
    definitions[path] = nil
end
location_batch_start = location_registration_order
for index, activate in ipairs(objects) do activate(registration, index) end
return true
end
function PackSanityStageItem(path, ...)
local arguments = table.pack(...)
stage_object('items', path, function(registration, index)
    local id = arguments[1]
    if registration > 1 then id = ('packsanity-item/%s/%d/%d'):format(path, index, registration) end
    PackSanitySeedItem(id, table.unpack(arguments, 2, arguments.n))
end)
end
function PackSanityStageLocation(path, ...)
local arguments = table.pack(...)
stage_object('locations', path, function() PackSanitySeedLocation(table.unpack(arguments, 1, arguments.n)) end)
end

