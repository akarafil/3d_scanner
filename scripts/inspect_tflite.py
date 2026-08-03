import struct, sys

def main(path):
    data = open(path, 'rb').read()
    if data[:4] != b'TFL3':
        print('Not TFL3:', data[:8]); return
    root_off = struct.unpack_from('<I', data, 4)[0]

    def table_field(table_pos, field_idx):
        soffset = struct.unpack_from('<i', data, table_pos)[0]
        vtable_pos = table_pos - soffset
        vtable_len = struct.unpack_from('<H', data, vtable_pos)[0]
        if 4 + field_idx*2 < vtable_len:
            off = struct.unpack_from('<H', data, vtable_pos + 4 + field_idx*2)[0]
            if off != 0:
                return table_pos + off
        return None

    def vec_len(pos):
        return struct.unpack_from('<I', data, pos)[0], pos + 4

    sub_f = table_field(root_off, 2)  # Model.subgraphs
    if sub_f is None: print('no subgraphs'); return
    n_s, s_first = vec_len(sub_f)
    sub0 = s_first + struct.unpack_from('<I', data, s_first)[0]

    tens_f = table_field(sub0, 0)
    in_f = table_field(sub0, 1)
    out_f = table_field(sub0, 2)
    n_t, t_first = vec_len(tens_f)
    n_i, i_first = vec_len(in_f)
    n_o, o_first = vec_len(out_f)
    in_idx = [struct.unpack_from('<I', data, i_first + i*4)[0] for i in range(n_i)]
    out_idx = [struct.unpack_from('<I', data, o_first + i*4)[0] for i in range(n_o)]

    TYPES = {0:'FLOAT32',1:'FLOAT16',2:'INT32',3:'UINT8',4:'INT64',5:'STRING',6:'COMPLEX64',7:'BOOL',8:'INT8',9:'FLOAT64',10:'COMPLEX128',11:'UINT64',12:'RESOURCE',13:'VARIANT',14:'UINT32',15:'UINT16',16:'INT16'}
    def tinfo(idx):
        tab = t_first + idx*4 + struct.unpack_from('<I', data, t_first + idx*4)[0]
        sh_f = table_field(tab, 0)
        shape = []
        if sh_f is not None:
            ln, first = vec_len(sh_f)
            shape = [struct.unpack_from('<i', data, first + i*4)[0] for i in range(ln)]
        ty_f = table_field(tab, 1)
        ttype = TYPES.get(data[ty_f], f'?{data[ty_f]}') if ty_f is not None else '?'
        return shape, ttype

    print('num_subgraphs:', n_s)
    print('inputs:', [(idx, tinfo(idx)) for idx in in_idx])
    print('outputs:', [(idx, tinfo(idx)) for idx in out_idx])

main(sys.argv[1])
