"""Inspect declared DEX field names only; never inspect or print field values.

R8 mapping files omit unchanged fields. Validate persisted schemas in the actual
APK using https://source.android.com/docs/core/runtime/dex-format instead.
"""
import re
import struct
from zipfile import ZipFile


def defined_fields(apk, class_names):
    wanted = {'L' + name.replace('.', '/') + ';': name for name in class_names}
    result = {}
    with ZipFile(apk) as archive:
        for member in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', member):
                continue
            data = archive.read(member)
            assert data[:4] == b'dex\n' and data[4:7] in (b'035', b'037', b'038', b'039', b'040'), 'Unsupported DEX format'

            def u32(offset):
                return struct.unpack_from('<I', data, offset)[0]

            assert u32(40) == 0x12345678, 'Unsupported DEX byte order'

            def uleb(offset):
                value = 0
                for shift in range(0, 35, 7):
                    byte = data[offset]
                    offset += 1
                    value |= (byte & 127) << shift
                    if byte < 128:
                        return value, offset
                raise ValueError('Invalid DEX ULEB128')

            string_ids = u32(60)
            type_ids = u32(68)
            field_ids = u32(84)
            strings = {}

            def string(index):
                if index not in strings:
                    _, start = uleb(u32(string_ids + index * 4))
                    # Only ASCII identifiers are compared; unrelated MUTF-8 names
                    # may contain surrogate/NUL encodings and are never printed.
                    strings[index] = data[start:data.index(0, start)].decode('utf-8', errors='replace')
                return strings[index]

            for i in range(u32(96)):
                definition = u32(100) + i * 32
                class_index = u32(definition)
                name = wanted.get(string(u32(type_ids + class_index * 4)))
                if name is None:
                    continue
                assert name not in result, 'Duplicate storage class'
                names = result[name] = set()
                cursor = u32(definition + 24)
                if not cursor:
                    continue
                static_count, cursor = uleb(cursor)
                instance_count, cursor = uleb(cursor)
                _, cursor = uleb(cursor)  # direct method count
                _, cursor = uleb(cursor)  # virtual method count
                for count in (static_count, instance_count):
                    index = 0
                    for _ in range(count):
                        difference, cursor = uleb(cursor)
                        _, cursor = uleb(cursor)  # access flags
                        index += difference
                        owner, _, field_name = struct.unpack_from('<HHI', data, field_ids + index * 8)
                        assert owner == class_index, 'Invalid DEX field owner'
                        names.add(string(field_name))
    return result
