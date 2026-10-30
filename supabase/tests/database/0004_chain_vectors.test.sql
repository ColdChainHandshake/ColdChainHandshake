-- Generated from docs/protocol/chain_vectors.json. ChainVectorsTest checks that the two match.
begin;

create extension if not exists pgtap with schema extensions;

select plan(19);

select is(private.chain_genesis('SHIP-VECTOR-1', '11111111-1111-1111-1111-111111111111'::uuid, 'vaccine-2-8-freeze-sensitive', 'LOG-1', 1700000000000), '89f314eda360a310c15e3389b28d4ae5111dbb27bb26ab7503a6cb47b6bf9c74', 'genesis SHIP-VECTOR-1');
select is(private.chain_genesis('SHIP-é-€', '11111111-1111-1111-1111-111111111111'::uuid, 'vaccine-2-8-freeze-sensitive', 'LOG-✓', 1700000000000), '2515fc6a4706e37a533d2b09555560f356dfaa1fbc21bd6936a99ea392f72943', 'genesis SHIP-é-€');
select is(private.chain_genesis('AB', '11111111-1111-1111-1111-111111111111'::uuid, 'vaccine-2-8-freeze-sensitive', 'C', 1), '8aaa79a6ce6dc76bc54862b58d40641f2378548124bbdaee4f0816c20c2e0283', 'genesis AB');
select is(private.chain_genesis('A', '11111111-1111-1111-1111-111111111111'::uuid, 'vaccine-2-8-freeze-sensitive', 'BC', 1), '76f27d7a79edcbe53196bb1fcb8cb1aee88790d49fa41dca820f01ff84c6ea22', 'genesis A');

select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 1, 1700000060000, 500, '89f314eda360a310c15e3389b28d4ae5111dbb27bb26ab7503a6cb47b6bf9c74'), '647dc6c1be22d610e63fc79a71a826f048659c2fbdcacdc176a741f528e33315', 'reading SHIP-VECTOR-1 #1');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 2, 1700000120000, 200, '647dc6c1be22d610e63fc79a71a826f048659c2fbdcacdc176a741f528e33315'), 'bb14fa126ac81f3ea191bbd65e9bbff21f56c8e7ccd9f61789358cbc26e65a88', 'reading SHIP-VECTOR-1 #2');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 3, 1700000180000, 800, 'bb14fa126ac81f3ea191bbd65e9bbff21f56c8e7ccd9f61789358cbc26e65a88'), '9ff445dffbd31caf44a70558c66bb2a3478f18f8dbde028e899a1a570038eead', 'reading SHIP-VECTOR-1 #3');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 4, 1700000240000, -350, '9ff445dffbd31caf44a70558c66bb2a3478f18f8dbde028e899a1a570038eead'), '5d73497baf612c54ac91e549c198ded72acd8b8a0860fb944399d076d6049c2e', 'reading SHIP-VECTOR-1 #4');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 5, 1700000300000, 1150, '5d73497baf612c54ac91e549c198ded72acd8b8a0860fb944399d076d6049c2e'), '9be6e33882739a04f2b18c3589238c22de48ec218bd323ee65f80a8fd6df94ed', 'reading SHIP-VECTOR-1 #5');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 6, 1700000360000, -2147483648, '9be6e33882739a04f2b18c3589238c22de48ec218bd323ee65f80a8fd6df94ed'), 'f49ec679fd242d9ee2c6e9c34a5e20d2b18b0893c87eca812c8055ea91c51e1e', 'reading SHIP-VECTOR-1 #6');
select is(private.reading_hash('SHIP-VECTOR-1', 'LOG-1', 7, 1700000420000, 2147483647, 'f49ec679fd242d9ee2c6e9c34a5e20d2b18b0893c87eca812c8055ea91c51e1e'), 'd63fb14cd4a2c4be841e0e8f3ff356341f43c13678fde211b2be484a30a9892a', 'reading SHIP-VECTOR-1 #7');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 1, 1700000060000, 500, '2515fc6a4706e37a533d2b09555560f356dfaa1fbc21bd6936a99ea392f72943'), 'cba6da384801098087b582bc76579bbd5a63fcb0ec141c851ff3e2a960fec2d3', 'reading SHIP-é-€ #1');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 2, 1700000120000, 200, 'cba6da384801098087b582bc76579bbd5a63fcb0ec141c851ff3e2a960fec2d3'), '288a36e85379a28c6c668042ca4981c5a2550758c8173ce91b5182bf3a26ed42', 'reading SHIP-é-€ #2');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 3, 1700000180000, 800, '288a36e85379a28c6c668042ca4981c5a2550758c8173ce91b5182bf3a26ed42'), 'd53b0ee3f56d90942d9b4c0e7b753d853a0aa1b8c0305c50b16429020cd43aa5', 'reading SHIP-é-€ #3');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 4, 1700000240000, -350, 'd53b0ee3f56d90942d9b4c0e7b753d853a0aa1b8c0305c50b16429020cd43aa5'), '4fac74e029bcaff5eae8b4694abe925c268e60f94280ef35be60d1f3e992492a', 'reading SHIP-é-€ #4');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 5, 1700000300000, 1150, '4fac74e029bcaff5eae8b4694abe925c268e60f94280ef35be60d1f3e992492a'), '3a22cf63f5d654e416518fe78cc9c0ad651be6c9b84c7bb32b8dcea769dc0b04', 'reading SHIP-é-€ #5');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 6, 1700000360000, -2147483648, '3a22cf63f5d654e416518fe78cc9c0ad651be6c9b84c7bb32b8dcea769dc0b04'), '5c8328c5d861aaa742551240fd2052db6e5c749348fbbbf9db080c27def02c0e', 'reading SHIP-é-€ #6');
select is(private.reading_hash('SHIP-é-€', 'LOG-✓', 7, 1700000420000, 2147483647, '5c8328c5d861aaa742551240fd2052db6e5c749348fbbbf9db080c27def02c0e'), '8ee007b9c6026354db5e285e77212303f43cdcc0c169e76cca156582f2c582aa', 'reading SHIP-é-€ #7');

select isnt(
  (select private.chain_genesis('AB', '11111111-1111-1111-1111-111111111111'::uuid, 'p', 'C', 1)),
  (select private.chain_genesis('A', '11111111-1111-1111-1111-111111111111'::uuid, 'p', 'BC', 1)),
  'moving a character between fields changes the hash'
);

select * from finish();

rollback;
