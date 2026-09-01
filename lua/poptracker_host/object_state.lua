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

