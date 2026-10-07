-- 서비스 이름을 IMS에서 inventory로 바꾸며 stock_adjustment_item의 컬럼 이름을 맞춘다 (wms_quantity와 짝)
ALTER TABLE stock_adjustment_item RENAME COLUMN ims_quantity TO inventory_quantity;
