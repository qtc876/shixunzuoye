import json
import os
from flask import Flask, request, jsonify, send_from_directory
from flask_cors import CORS
from datetime import datetime

app = Flask(__name__)
CORS(app)

DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'products.json')


def load_products():
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, 'r', encoding='utf-8') as f:
        return json.load(f)


def save_products(products):
    with open(DATA_FILE, 'w', encoding='utf-8') as f:
        json.dump(products, f, ensure_ascii=False, indent=2)


def generate_id(products):
    if not products:
        return 'P001'
    max_num = 0
    for p in products:
        try:
            num = int(p['productId'][1:])
            if num > max_num:
                max_num = num
        except (ValueError, IndexError):
            pass
    return f'P{max_num + 1:03d}'


@app.route('/')
def index():
    return send_from_directory('.', 'index.html')


@app.route('/api/products', methods=['GET'])
def get_products():
    products = load_products()
    keyword = request.args.get('keyword', '').strip()
    category = request.args.get('category', '').strip()
    status = request.args.get('status', '').strip()
    sort = request.args.get('sort', '').strip()
    
    if keyword:
        products = [p for p in products if keyword.lower() in p['productId'].lower() 
                    or keyword.lower() in p['productName'].lower()
                    or keyword.lower() in p.get('brand', '').lower()
                    or keyword.lower() in p.get('supplier', '').lower()]
    if category:
        products = [p for p in products if p['category'] == category]
    if status:
        products = [p for p in products if p.get('status', 'active') == status]
    
    if sort:
        reverse = False
        key = 'productId'
        if sort == 'price-asc':
            key = 'price'
        elif sort == 'price-desc':
            key = 'price'
            reverse = True
        elif sort == 'stock-asc':
            key = 'stock'
        elif sort == 'stock-desc':
            key = 'stock'
            reverse = True
        elif sort == 'sold-desc':
            key = 'soldCount'
            reverse = True
        elif sort == 'name-asc':
            key = 'productName'
        products = sorted(products, key=lambda x: x.get(key, 0), reverse=reverse)
    
    return jsonify(products)


@app.route('/api/products/<product_id>', methods=['GET'])
def get_product(product_id):
    products = load_products()
    product = next((p for p in products if p['productId'] == product_id), None)
    if product:
        return jsonify(product)
    return jsonify({'error': '产品不存在'}), 404


@app.route('/api/products', methods=['POST'])
def add_product():
    products = load_products()
    data = request.get_json()
    
    existing = next((p for p in products if p['productId'] == data.get('productId')), None)
    if existing:
        return jsonify({'error': '产品号已存在'}), 400
    
    product = {
        'productId': data.get('productId') or generate_id(products),
        'productName': data.get('productName', ''),
        'brand': data.get('brand', ''),
        'category': data.get('category', '其他'),
        'spec': data.get('spec', ''),
        'price': float(data.get('price', 0)),
        'stock': int(data.get('stock', 0)),
        'soldCount': int(data.get('soldCount', 0)),
        'newArrival': int(data.get('newArrival', 0)),
        'lowStock': int(data.get('lowStock', 10)),
        'origin': data.get('origin', ''),
        'shelfLife': data.get('shelfLife', ''),
        'supplier': data.get('supplier', ''),
        'description': data.get('description', ''),
        'image': data.get('image', ''),
        'status': data.get('status', 'active'),
        'createTime': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
        'updateTime': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    }
    
    products.append(product)
    save_products(products)
    return jsonify(product), 201


@app.route('/api/products/<product_id>', methods=['PUT'])
def update_product(product_id):
    products = load_products()
    data = request.get_json()
    
    index = next((i for i, p in enumerate(products) if p['productId'] == product_id), None)
    if index is None:
        return jsonify({'error': '产品不存在'}), 404
    
    product = products[index]
    product['productName'] = data.get('productName', product['productName'])
    product['brand'] = data.get('brand', product.get('brand', ''))
    product['category'] = data.get('category', product['category'])
    product['spec'] = data.get('spec', product.get('spec', ''))
    product['price'] = float(data.get('price', product['price']))
    product['stock'] = int(data.get('stock', product['stock']))
    product['soldCount'] = int(data.get('soldCount', product.get('soldCount', 0)))
    product['newArrival'] = int(data.get('newArrival', product.get('newArrival', 0)))
    product['lowStock'] = int(data.get('lowStock', product.get('lowStock', 10)))
    product['origin'] = data.get('origin', product.get('origin', ''))
    product['shelfLife'] = data.get('shelfLife', product.get('shelfLife', ''))
    product['supplier'] = data.get('supplier', product.get('supplier', ''))
    product['description'] = data.get('description', product.get('description', ''))
    product['image'] = data.get('image', product.get('image', ''))
    product['status'] = data.get('status', product.get('status', 'active'))
    product['updateTime'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    save_products(products)
    return jsonify(product)


@app.route('/api/products/<product_id>', methods=['DELETE'])
def delete_product(product_id):
    products = load_products()
    index = next((i for i, p in enumerate(products) if p['productId'] == product_id), None)
    if index is None:
        return jsonify({'error': '产品不存在'}), 404
    
    deleted = products.pop(index)
    save_products(products)
    return jsonify({'message': '删除成功', 'product': deleted})


@app.route('/api/products/<product_id>/sale', methods=['POST'])
def sale_product(product_id):
    products = load_products()
    data = request.get_json()
    quantity = int(data.get('quantity', 1))
    
    index = next((i for i, p in enumerate(products) if p['productId'] == product_id), None)
    if index is None:
        return jsonify({'error': '产品不存在'}), 404
    
    product = products[index]
    if product['stock'] < quantity:
        return jsonify({'error': '库存不足'}), 400
    
    product['stock'] -= quantity
    product['soldCount'] += quantity
    product['updateTime'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    save_products(products)
    return jsonify(product)


@app.route('/api/products/<product_id>/restock', methods=['POST'])
def restock_product(product_id):
    products = load_products()
    data = request.get_json()
    quantity = int(data.get('quantity', 1))
    
    index = next((i for i, p in enumerate(products) if p['productId'] == product_id), None)
    if index is None:
        return jsonify({'error': '产品不存在'}), 404
    
    product = products[index]
    product['stock'] += quantity
    product['newArrival'] += quantity
    product['updateTime'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    save_products(products)
    return jsonify(product)


@app.route('/api/categories', methods=['GET'])
def get_categories():
    products = load_products()
    categories = list(set(p['category'] for p in products))
    return jsonify(sorted(categories))


@app.route('/api/stats', methods=['GET'])
def get_stats():
    products = load_products()
    total_products = len(products)
    total_stock = sum(p['stock'] for p in products)
    total_sold = sum(p['soldCount'] for p in products)
    total_value = sum(p['stock'] * p['price'] for p in products)
    low_stock = sum(1 for p in products if p['stock'] < p.get('lowStock', 10))
    active_products = sum(1 for p in products if p.get('status', 'active') == 'active')
    
    return jsonify({
        'totalProducts': total_products,
        'totalStock': total_stock,
        'totalSold': total_sold,
        'totalValue': round(total_value, 2),
        'lowStock': low_stock,
        'activeProducts': active_products
    })


if __name__ == '__main__':
    if not os.path.exists(DATA_FILE):
        initial_data = [
            {
                'productId': 'P001',
                'productName': '农夫山泉矿泉水',
                'brand': '农夫山泉',
                'category': '饮料',
                'spec': '550ml',
                'price': 2.5,
                'stock': 120,
                'soldCount': 80,
                'newArrival': 50,
                'lowStock': 10,
                'origin': '浙江杭州',
                'shelfLife': '24个月',
                'supplier': '农夫山泉股份有限公司',
                'description': '天然矿泉水，富含多种矿物质',
                'image': '',
                'status': 'active',
                'createTime': '2025-01-10 09:00:00',
                'updateTime': '2025-06-20 14:30:00'
            },
            {
                'productId': 'P002',
                'productName': '康师傅红烧牛肉面',
                'brand': '康师傅',
                'category': '方便食品',
                'spec': '108g/桶',
                'price': 4.5,
                'stock': 85,
                'soldCount': 115,
                'newArrival': 30,
                'lowStock': 10,
                'origin': '天津',
                'shelfLife': '6个月',
                'supplier': '康师傅控股有限公司',
                'description': '经典红烧牛肉味方便面',
                'image': '',
                'status': 'active',
                'createTime': '2025-01-12 10:00:00',
                'updateTime': '2025-06-21 11:20:00'
            },
            {
                'productId': 'P003',
                'productName': '伊利纯牛奶',
                'brand': '伊利',
                'category': '乳制品',
                'spec': '250ml/盒',
                'price': 6.8,
                'stock': 60,
                'soldCount': 90,
                'newArrival': 40,
                'lowStock': 15,
                'origin': '内蒙古',
                'shelfLife': '6个月',
                'supplier': '内蒙古伊利实业集团',
                'description': '100%纯牛奶，营养丰富',
                'image': '',
                'status': 'active',
                'createTime': '2025-01-15 08:30:00',
                'updateTime': '2025-06-22 09:15:00'
            },
            {
                'productId': 'P004',
                'productName': '乐事薯片原味',
                'brand': '乐事',
                'category': '零食',
                'spec': '75g/袋',
                'price': 8.9,
                'stock': 45,
                'soldCount': 55,
                'newArrival': 20,
                'lowStock': 10,
                'origin': '上海',
                'shelfLife': '9个月',
                'supplier': '百事食品（中国）有限公司',
                'description': '原味薯片，香脆可口',
                'image': '',
                'status': 'active',
                'createTime': '2025-01-20 11:00:00',
                'updateTime': '2025-06-18 16:45:00'
            },
            {
                'productId': 'P005',
                'productName': '海天酱油生抽',
                'brand': '海天',
                'category': '调味品',
                'spec': '500ml/瓶',
                'price': 12.5,
                'stock': 8,
                'soldCount': 42,
                'newArrival': 0,
                'lowStock': 10,
                'origin': '广东佛山',
                'shelfLife': '18个月',
                'supplier': '佛山市海天调味食品股份有限公司',
                'description': '酿造生抽，鲜味十足',
                'image': '',
                'status': 'active',
                'createTime': '2025-02-01 13:00:00',
                'updateTime': '2025-06-15 10:00:00'
            },
            {
                'productId': 'P006',
                'productName': '苹果（红富士）',
                'brand': '烟台苹果',
                'category': '水果',
                'spec': '500g',
                'price': 9.9,
                'stock': 30,
                'soldCount': 70,
                'newArrival': 25,
                'lowStock': 5,
                'origin': '山东烟台',
                'shelfLife': '1个月',
                'supplier': '山东烟台苹果基地',
                'description': '红富士苹果，脆甜多汁',
                'image': '',
                'status': 'active',
                'createTime': '2025-02-10 08:00:00',
                'updateTime': '2025-06-23 08:30:00'
            },
            {
                'productId': 'P007',
                'productName': '雪花啤酒勇闯天涯',
                'brand': '雪花',
                'category': '酒水',
                'spec': '500ml/罐',
                'price': 5.5,
                'stock': 200,
                'soldCount': 150,
                'newArrival': 100,
                'lowStock': 20,
                'origin': '辽宁沈阳',
                'shelfLife': '12个月',
                'supplier': '华润雪花啤酒有限公司',
                'description': '清爽型啤酒，口感纯正',
                'image': '',
                'status': 'active',
                'createTime': '2025-02-15 09:30:00',
                'updateTime': '2025-06-20 17:00:00'
            },
            {
                'productId': 'P008',
                'productName': '奥利奥夹心饼干',
                'brand': '奥利奥',
                'category': '零食',
                'spec': '116g/盒',
                'price': 11.8,
                'stock': 55,
                'soldCount': 45,
                'newArrival': 30,
                'lowStock': 10,
                'origin': '上海',
                'shelfLife': '9个月',
                'supplier': '亿滋食品企业管理（上海）有限公司',
                'description': '巧克力夹心饼干',
                'image': '',
                'status': 'active',
                'createTime': '2025-03-01 10:00:00',
                'updateTime': '2025-06-19 15:20:00'
            },
            {
                'productId': 'P009',
                'productName': '金龙鱼调和油',
                'brand': '金龙鱼',
                'category': '粮油',
                'spec': '5L/桶',
                'price': 69.9,
                'stock': 25,
                'soldCount': 15,
                'newArrival': 10,
                'lowStock': 5,
                'origin': '广东深圳',
                'shelfLife': '18个月',
                'supplier': '益海嘉里金龙鱼粮油食品',
                'description': '食用调和油，健康营养',
                'image': '',
                'status': 'active',
                'createTime': '2025-03-05 10:00:00',
                'updateTime': '2025-06-20 15:20:00'
            },
            {
                'productId': 'P010',
                'productName': '三全速冻饺子',
                'brand': '三全',
                'category': '冷冻食品',
                'spec': '500g/袋',
                'price': 15.8,
                'stock': 40,
                'soldCount': 30,
                'newArrival': 20,
                'lowStock': 10,
                'origin': '河南郑州',
                'shelfLife': '12个月',
                'supplier': '三全食品股份有限公司',
                'description': '猪肉白菜速冻水饺',
                'image': '',
                'status': 'inactive',
                'createTime': '2025-03-10 10:00:00',
                'updateTime': '2025-06-18 15:20:00'
            }
        ]
        save_products(initial_data)
    
    app.run(debug=True, port=5000)
