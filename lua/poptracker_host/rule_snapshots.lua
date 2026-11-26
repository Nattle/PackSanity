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
if type(kind) == 'string' and type(path) == 'string' then load_registered_paths(kind, path) end
end

function PackSanityLocationHighlights(_)
local records = {}
local seen = {}
for _, object in pairs(tracker_objects) do
    if object.__packsanity_location_placeholder then
        object = find_location_reference(object.__packsanity_location_id) or object
    end
    local id = object.__packsanity_location_id
    if id and not seen[id] then
        seen[id] = true
        table.insert(records, id..'\30'..tostring(object.Highlight or Highlight.None))
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
