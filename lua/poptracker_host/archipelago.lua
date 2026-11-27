Archipelago = {
PlayerNumber = -1, TeamNumber = -1, Seed = nil,
MissingLocations = {}, CheckedLocations = {},
ClientStatus = { UNKNOWN = 0, CONNECTED = 5, READY = 10, PLAYING = 20, GOAL = 30 },
__connected = false, __allows_location_checks = false, __allows_location_scouts = false,
__player_aliases = {}, __player_games = {},
__item_names = {}, __location_names = {},
}
function PackSanitySetArchipelagoFlags(flags)
local manual = list_contains_identity(flags, 'apmanual')
Archipelago.__allows_location_checks = manual
Archipelago.__allows_location_scouts = manual or list_contains_identity(flags, 'aphintgame')
end

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
if type(locations) ~= 'table' then return false end
local ids = {}
for _, location_id in ipairs(locations) do
    local id = type(location_id) == 'number' and math.tointeger(location_id)
    if not id then return false end
    table.insert(ids, tostring(id))
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
if not self.__allows_location_checks then return false end
local code = type(status) == 'number' and math.tointeger(status)
if not code then return false end
table.insert(ap_commands, 'status\30'..code)
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
    if not list_contains_identity(Archipelago.CheckedLocations, location_id) then
        table.insert(Archipelago.CheckedLocations, location_id)
    end
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

