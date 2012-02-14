data = {"Fruit": ["orange", "pear"], "Colour": ["orange", "red"]}

IDs = {}    # build a list of (ID, table_name) pairs

tables = ['Fruit','Colour'];
for table in tables:
  rows = data[table]
  for row in rows:
   if IDs.has_key(row):
    print "Duplicate ID %s is present in both %s and %s" % (row, table, IDs[row])
   else:
    IDs[row] = table

