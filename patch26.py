p='backend/server/src/test/java/com/erp/support/TestDataFactory.java'
s=open(p,encoding='utf-8').read()
old="""                TRUNCATE inventory_ledger, inventory_reservations, inventory_balances,
                         sales_order_line_reservations, return_order_lines,
                         purchase_order_lines, sales_order_lines,
                         return_orders, purchase_orders, sales_orders,
                         daily_settlements, settlement_periods,
                         document_sequences, idempotency_keys,
                         skus, products, warehouses, suppliers, customers,
                         app_user_roles, app_users
                RESTART IDENTITY CASCADE"""
new="""                TRUNCATE inventory_ledger, inventory_reservations, inventory_balances,
                         sales_order_line_reservations, return_order_lines,
                         purchase_order_lines, sales_order_lines,
                         return_orders, purchase_orders, sales_orders,
                         daily_settlements, settlement_periods,
                         document_sequences, idempotency_keys,
                         skus, products, warehouses, suppliers, customers,
                         approval_steps, approval_instances, approval_flows,
                         data_scopes,
                         app_user_roles, app_users
                RESTART IDENTITY CASCADE"""
assert old in s
s=s.replace(old,new,1)
open(p,'w',encoding='utf-8').write(s)
print("truncateAll extended")
