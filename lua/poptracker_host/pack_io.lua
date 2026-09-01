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
