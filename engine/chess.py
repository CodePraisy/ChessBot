from chessEngine import Chess

board = Chess()

board.print_board()
valid = board.move(1, [6, 5], [5, 5])
board.print_board()

if False:
    valid = board.move(1, [6, 3], [4, 3])
    print(valid)
    board.print_board()

    valid = board.move(-1, [1, 3], [3, 3])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 1], [5, 2])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 6], [2, 5])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 2], [4, 5])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 2], [3, 5])
    print(valid)
    board.print_board()

    valid = board.move(1, [6, 0], [4, 0])
    print(valid)
    board.print_board()

    valid = board.move(-1, [1, 0], [3, 0])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 0], [5, 0])
    print(valid)
    board.print_board()

    valid = board.move(-1, [1, 1], [2, 1])
    print(valid)
    board.print_board()

    valid = board.move(1, [5, 0], [5, 1])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 3], [0, 2])
    print(valid)
    board.print_board()

    valid = board.move(1, [5, 1], [3, 1])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 2], [2, 4])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 3], [7, 0])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 4], [1, 3])
    print(valid)
    board.print_board()

    valid = board.move(1, [6, 4], [5, 4])
    print(valid)
    board.print_board()

    valid = board.move(-1, [2, 4], [5, 4])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 0], [5, 0])
    print(valid)
    board.print_board()

if False:
    valid = board.move(1, [6, 3], [4, 3])
    print(valid)
    board.print_board()

    valid = board.move(-1, [1, 1], [2, 1])
    print(valid)
    board.print_board()

    valid = board.move(1, [4, 3], [3, 3])
    print(valid)
    board.print_board()

    valid = board.move(-1, [1, 2], [3, 2])
    print(valid)
    board.print_board()

    valid = board.move(1, [3, 3], [2, 2])
    print(valid)
    board.print_board()

    valid = board.move(-1, [0, 1], [2, 0])
    print(valid)
    board.print_board()

    valid = board.move(1, [7, 1], [5, 2])
    print(valid)
    board.print_board()