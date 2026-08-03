import struct, sys

def main(path):
    data = open(path, 'rb').read()

    # Standard TFLite layout: root offset (u32) at offset 0, magic "TFL3" at offset 4.
    if data[4:8] == b'TFL3':
        root_off = struct.unpack_from('<I', data, 0)[0]
        print('magic: TFL3 @ offset 4 (standard TFLite layout)')
    elif data[:4] == b'TFL3':
        root_off = struct.unpack_from('<I', data, 4)[0]
        print('magic: TFL3 @ offset 0 (legacy/alternate layout)')
    else:
        print('Not a TFLite file. Header:', data[:8].hex()); return

    def table_field(table_pos, field_idx):
        # Returns the field position in the table (where the field value is stored).
        soffset = struct.unpack_from('<i', data, table_pos)[0]
        vtable_pos = table_pos - soffset
        vtable_len = struct.unpack_from('<H', data, vtable_pos)[0]
        if 4 + field_idx*2 < vtable_len:
            off = struct.unpack_from('<H', data, vtable_pos + 4 + field_idx*2)[0]
            if off != 0:
                return table_pos + off
        return None

    def follow_uoffset(field_pos):
        # A table/string/vector field stores a uoffset relative to its own position.
        return field_pos + struct.unpack_from('<I', data, field_pos)[0]

    def vec_len_at_field(field_pos):
        # field_pos -> uoffset -> vector start; returns (length, first_element_pos)
        vec_pos = follow_uoffset(field_pos)
        return struct.unpack_from('<I', data, vec_pos)[0], vec_pos + 4

    def read_string(field_pos):
        if field_pos is None:
            return None
        s_pos = follow_uoffset(field_pos)
        ln = struct.unpack_from('<I', data, s_pos)[0]
        raw = data[s_pos+4:s_pos+4+ln]
        try:
            return raw.decode('utf-8')
        except UnicodeDecodeError:
            return raw.decode('latin-1')

    subg_field = table_field(root_off, 2)  # Model.subgraphs
    if subg_field is None:
        print('no subgraphs'); return
    n_s, s_first = vec_len_at_field(subg_field)
    sub0 = s_first + struct.unpack_from('<I', data, s_first)[0]

    tens_field = table_field(sub0, 0)
    in_field = table_field(sub0, 1)
    out_field = table_field(sub0, 2)
    n_t, t_first = vec_len_at_field(tens_field)
    n_i, i_first = vec_len_at_field(in_field)
    n_o, o_first = vec_len_at_field(out_field)
    in_idx = [struct.unpack_from('<I', data, i_first + i*4)[0] for i in range(n_i)]
    out_idx = [struct.unpack_from('<I', data, o_first + i*4)[0] for i in range(n_o)]

    TYPES = {0:'FLOAT32',1:'FLOAT16',2:'INT32',3:'UINT8',4:'INT64',5:'STRING',6:'COMPLEX64',7:'BOOL',8:'INT8',9:'FLOAT64',10:'COMPLEX128',11:'UINT64',12:'RESOURCE',13:'VARIANT',14:'UINT32',15:'UINT16',16:'INT16'}
    def tinfo(idx):
        tab = t_first + idx*4 + struct.unpack_from('<I', data, t_first + idx*4)[0]
        sh_field = table_field(tab, 0)
        shape = []
        if sh_field is not None:
            ln, first = vec_len_at_field(sh_field)
            shape = [struct.unpack_from('<i', data, first + i*4)[0] for i in range(ln)]
        ty_field = table_field(tab, 1)
        if ty_field is not None:
            ttype = TYPES.get(data[ty_field], f'?{data[ty_field]}')
        else:
            # Absent field -> flatbuffer default. TensorType default is 0 = FLOAT32.
            ttype = 'FLOAT32'
        name = read_string(table_field(tab, 3))
        return shape, ttype, name

    print('num_subgraphs:', n_s)
    print('inputs:', [(idx, tinfo(idx)) for idx in in_idx])
    print('outputs:', [(idx, tinfo(idx)) for idx in out_idx])

main(sys.argv[1])
